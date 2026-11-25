package com.dsuttz.yaba.torn;

import com.dsuttz.yaba.config.YabaProperties;
import com.dsuttz.yaba.store.KeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Shared rate limiter. Torn's limit is per PLAYER, so keys are grouped by owner and each
 * owner gets a sliding 60-second window of rpm-per-player calls. Shared by every run, so a
 * prospect check during the nightly run can't push anyone over the limit.
 */
@Component
public class KeyPool {

    private static final Logger log = LoggerFactory.getLogger(KeyPool.class);
    private static final long WINDOW_MS = 60_000;

    public record Leased(long id, String key) { }

    private final KeyRepository repo;
    private final int rpm;
    private final Map<Long, Deque<Long>> windows = new HashMap<>();      // owner -> call times
    private final Map<Long, List<Leased>> byOwner = new LinkedHashMap<>();
    private final Map<Long, Integer> rr = new HashMap<>();
    private long loadedAt = 0;

    public KeyPool(KeyRepository repo, YabaProperties props) {
        this.repo = repo;
        this.rpm = Math.max(1, props.rpmPerPlayer());
    }

    /** Reloads healthy keys from the database (cheap; called at the start of each phase). */
    public synchronized void reload() {
        byOwner.clear();
        for (KeyRepository.StoredKey k : repo.healthy()) {
            long owner = k.ownerTornId() != null ? k.ownerTornId() : -k.id();
            byOwner.computeIfAbsent(owner, o -> new ArrayList<>()).add(new Leased(k.id(), k.key()));
        }
        loadedAt = System.currentTimeMillis();
    }

    public synchronized int players() {
        if (loadedAt == 0) reload();
        return byOwner.size();
    }

    /** Blocks until a key with spare capacity is available. Throws if no healthy keys remain. */
    public Leased acquire() throws InterruptedException {
        synchronized (this) {
            if (loadedAt == 0) reload();
            while (true) {
                if (byOwner.isEmpty()) throw new IllegalStateException("No healthy Torn API keys");
                long now = System.currentTimeMillis();
                long earliest = Long.MAX_VALUE;
                for (Map.Entry<Long, List<Leased>> e : byOwner.entrySet()) {
                    Deque<Long> w = windows.computeIfAbsent(e.getKey(), o -> new ArrayDeque<>());
                    while (!w.isEmpty() && now - w.peekFirst() >= WINDOW_MS) w.pollFirst();
                    if (w.size() < rpm) {
                        w.addLast(now);
                        int i = rr.merge(e.getKey(), 1, Integer::sum);
                        List<Leased> keys = e.getValue();
                        return keys.get(i % keys.size());
                    }
                    earliest = Math.min(earliest, w.peekFirst() + WINDOW_MS);
                }
                wait(Math.max(50, earliest - now));
            }
        }
    }

    /** Takes a key out of rotation (bad/disabled key) and records why. */
    public synchronized void markBad(Leased key, String reason) {
        log.warn("Key {} marked unhealthy: {}", key.id(), reason);
        repo.markUnhealthy(key.id(), reason);
        byOwner.values().forEach(l -> l.removeIf(k -> k.id() == key.id()));
        byOwner.values().removeIf(List::isEmpty);
        notifyAll();
    }

    /** After a "too many requests" error: treat that owner's window as full for a minute. */
    public synchronized void saturate(Leased key) {
        byOwner.forEach((owner, keys) -> {
            if (keys.stream().anyMatch(k -> k.id() == key.id())) {
                Deque<Long> w = windows.computeIfAbsent(owner, o -> new ArrayDeque<>());
                long now = System.currentTimeMillis();
                while (w.size() < rpm) w.addLast(now);
            }
        });
    }
}
