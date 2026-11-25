package com.dsuttz.yaba.collect;

import com.dsuttz.yaba.bsp.BspClient;
import com.dsuttz.yaba.config.YabaProperties;
import com.dsuttz.yaba.store.DataRepository;
import com.dsuttz.yaba.store.DataRepository.RosterMember;
import com.dsuttz.yaba.store.DataRepository.Slot;
import com.dsuttz.yaba.store.KeyRepository;
import com.dsuttz.yaba.store.RunRepository;
import com.dsuttz.yaba.torn.KeyPool;
import com.dsuttz.yaba.torn.TornApiException;
import com.dsuttz.yaba.torn.TornClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs collections: rosters → personal stats (now / 30d / 90d) → BSP.
 * One YABA run and one run per prospective faction can run at the same time; they share the
 * KeyPool, so Torn's per-player limit holds across all of them.
 */
@Service
public class CollectorService {

    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);
    private static final String STAT_LIST = "xantaken,timeplayed,rankedwarhits,revives";
    private static final String[] STATS = STAT_LIST.split(",");
    private static final int MAX_ATTEMPTS = 5;
    private static final int STAT_CONCURRENCY = 32;

    private final TornClient torn;
    private final BspClient bsp;
    private final KeyPool pool;
    private final KeyRepository keys;
    private final DataRepository data;
    private final RunRepository runs;
    private final YabaProperties props;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    private Long yabaRunId = null;
    private final Map<Long, Long> prospectRuns = new ConcurrentHashMap<>();

    public CollectorService(TornClient torn, BspClient bsp, KeyPool pool, KeyRepository keys, DataRepository data,
                            RunRepository runs, YabaProperties props) {
        this.torn = torn;
        this.bsp = bsp;
        this.pool = pool;
        this.keys = keys;
        this.data = data;
        this.runs = runs;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int n = runs.failOrphans();
        if (n > 0) log.info("Marked {} interrupted run(s) as failed", n);
    }

    // ------------------------------------------------------------------ starting runs

    /** Starts a YABA run, or returns the one already running. */
    public synchronized RunRepository.Run startYaba(boolean rosterOnly) {
        if (yabaRunId != null) return runs.find(yabaRunId).orElseThrow();
        List<Long> ids = data.yabaFactions().stream().map(DataRepository.Faction::id).toList();
        if (ids.isEmpty()) throw new IllegalStateException("No YABA factions yet — push the list from the sheet first");
        if (keys.healthy().isEmpty()) throw new IllegalStateException("No healthy Torn API keys — add keys from the sheet");
        long id = runs.start("yaba", null, null, rosterOnly);
        yabaRunId = id;
        exec.submit(() -> execute(id, ids, rosterOnly, () -> {
            synchronized (this) { yabaRunId = null; }
        }));
        return runs.find(id).orElseThrow();
    }

    /** Starts a check of one prospective faction, or returns the one already running for it. */
    public synchronized RunRepository.Run startProspect(long factionId, String type) {
        Long running = prospectRuns.get(factionId);
        if (running != null) return runs.find(running).orElseThrow();
        if (keys.healthy().isEmpty()) throw new IllegalStateException("No healthy Torn API keys — add keys from the sheet");
        data.ensureProspect(factionId, type);
        long id = runs.start("prospect", factionId, type, false);
        prospectRuns.put(factionId, id);
        exec.submit(() -> execute(id, List.of(factionId), false, () -> prospectRuns.remove(factionId)));
        return runs.find(id).orElseThrow();
    }

    // ------------------------------------------------------------------ the run

    private void execute(long runId, List<Long> factionIds, boolean rosterOnly, Runnable onDone) {
        List<String> notes = new ArrayList<>();
        try {
            pool.reload();
            notes.add(roster(runId, factionIds));
            if (!rosterOnly) {
                notes.add(stats(runId, factionIds));
                notes.add(bspPhase(runId, factionIds));
            }
            runs.finish(runId, "done", String.join(" · ", notes));
            log.info("Run {} done: {}", runId, notes);
        } catch (Exception e) {
            log.error("Run {} failed", runId, e);
            notes.add("FAILED: " + e.getMessage());
            runs.finish(runId, "failed", String.join(" · ", notes));
        } finally {
            onDone.run();
        }
    }

    // ------------------------------------------------------------------ phase 1: rosters

    private String roster(long runId, List<Long> factionIds) throws InterruptedException {
        runs.progress(runId, "roster", 0, factionIds.size());
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (long fid : factionIds) {
            fs.add(exec.submit(() -> {
                try {
                    JsonNode basicJ = call("/faction/" + fid + "/basic");
                    JsonNode membersJ = call("/faction/" + fid + "/members?striptags=true");
                    JsonNode b = basicJ.has("basic") ? basicJ.get("basic") : basicJ;
                    data.saveRoster(fid, b.path("name").asText(null), longOrNull(b.path("leader_id")),
                            longOrNull(b.path("co_leader_id")), b.path("capacity").isNumber() ? b.get("capacity").asInt() : null,
                            parseMembers(membersJ.path("members")));
                } catch (TornApiException e) {
                    failed.incrementAndGet();
                    data.rosterError(fid, "API error " + e.code() + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                runs.progress(runId, "roster", done.incrementAndGet(), factionIds.size());
                return null;
            }));
        }
        waitAll(fs);
        return "Rosters " + (factionIds.size() - failed.get()) + "/" + factionIds.size();
    }

    static List<RosterMember> parseMembers(JsonNode m) {
        List<RosterMember> out = new ArrayList<>();
        if (m.isArray()) {
            m.forEach(x -> out.add(member(x.path("id").asLong(), x)));
        } else if (m.isObject()) {
            m.fields().forEachRemaining(e -> out.add(member(Long.parseLong(e.getKey()), e.getValue())));
        }
        return out;
    }

    private static RosterMember member(long id, JsonNode x) {
        JsonNode la = x.path("last_action");
        return new RosterMember(id, x.path("name").asText(null),
                x.path("level").isNumber() ? x.get("level").asInt() : null,
                x.path("position").asText(null),
                x.path("days_in_faction").isNumber() ? x.get("days_in_faction").asInt() : null,
                la.path("relative").asText(null),
                la.path("timestamp").isNumber() ? la.get("timestamp").asLong() : null,
                x.path("status").path("state").asText(null));
    }

    // ------------------------------------------------------------------ phase 2: personal stats

    private String stats(long runId, List<Long> factionIds) throws InterruptedException {
        List<DataRepository.StatNeed> needs = data.statNeeds(factionIds, props.statsCacheHours());
        long now = Instant.now().getEpochSecond();
        long ts30 = now - 30L * 86400, ts90 = now - 90L * 86400;
        record Task(long id, Slot slot, Long ts) { }
        List<Task> tasks = new ArrayList<>();
        for (var n : needs) {
            if (n.now()) tasks.add(new Task(n.tornId(), Slot.NOW, null));
            if (n.d30()) tasks.add(new Task(n.tornId(), Slot.D30, ts30));
            if (n.d90()) tasks.add(new Task(n.tornId(), Slot.D90, ts90));
        }
        int total = tasks.size();
        data.clearErrors(needs.stream().map(DataRepository.StatNeed::tornId).toList());
        runs.progress(runId, "stats", 0, total);
        if (total == 0) return "Stats: all cached";

        Semaphore inFlight = new Semaphore(STAT_CONCURRENCY);
        AtomicInteger done = new AtomicInteger(), errors = new AtomicInteger();
        AtomicBoolean abort = new AtomicBoolean();
        List<Future<?>> fs = new ArrayList<>(total);
        for (Task t : tasks) {
            if (abort.get()) break;
            inFlight.acquire();
            fs.add(exec.submit(() -> {
                try {
                    String path = "/user/" + t.id() + "/personalstats?stat=" + STAT_LIST
                            + (t.ts() != null ? "&timestamp=" + t.ts() : "");
                    Long[] vals = extractStats(call(path));
                    if (Arrays.stream(vals).allMatch(Objects::isNull)) {
                        data.saveSlotError(t.id(), t.slot(), "No personal stats returned");
                        errors.incrementAndGet();
                    } else {
                        // a stat missing from a historical snapshot means it was 0 back then
                        for (int i = 0; i < vals.length; i++) if (vals[i] == null) vals[i] = 0L;
                        data.saveSlot(t.id(), t.slot(), vals);
                    }
                } catch (TornApiException e) {
                    data.saveSlotError(t.id(), t.slot(), "API " + e.code() + ": " + e.getMessage());
                    errors.incrementAndGet();
                } catch (IllegalStateException e) {   // no keys left
                    abort.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.release();
                    int d = done.incrementAndGet();
                    if (d % 50 == 0 || d == total) runs.progress(runId, "stats", d, total);
                }
                return null;
            }));
        }
        waitAll(fs);
        if (abort.get()) throw new IllegalStateException("All Torn API keys failed during stats");
        return "Stats " + total + " calls" + (errors.get() > 0 ? " (" + errors.get() + " errors)" : "");
    }

    /** v2 returns personalstats as [{name, value}]; also handles nested-object forms. */
    static Long[] extractStats(JsonNode json) {
        Map<String, Long> found = new HashMap<>();
        Set<String> want = Set.of(STATS);
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(json.has("personalstats") ? json.get("personalstats") : json);
        while (!stack.isEmpty()) {
            JsonNode n = stack.pop();
            if (n.isArray()) {
                for (JsonNode x : n) {
                    if (x.has("name") && x.has("value") && want.contains(x.get("name").asText())) {
                        found.put(x.get("name").asText(), x.get("value").asLong());
                    } else stack.push(x);
                }
            } else if (n.isObject()) {
                n.fields().forEachRemaining(e -> {
                    if (want.contains(e.getKey()) && e.getValue().isNumber()) found.put(e.getKey(), e.getValue().asLong());
                    else if (e.getValue().isContainerNode()) stack.push(e.getValue());
                });
            }
        }
        Long[] out = new Long[STATS.length];
        for (int i = 0; i < STATS.length; i++) out[i] = found.get(STATS[i]);
        return out;
    }

    // ------------------------------------------------------------------ phase 3: BSP

    private String bspPhase(long runId, List<Long> factionIds) throws InterruptedException {
        refreshBspSubscriptions();
        List<String> bspKeys = keys.bspActive().stream().map(KeyRepository.StoredKey::key).toList();
        if (bspKeys.isEmpty()) return "BSP: no key with an active subscription";
        List<Long> ids = data.bspNeeds(factionIds, props.bspCacheDays());
        int total = ids.size();
        runs.progress(runId, "bsp", 0, total);
        if (total == 0) return "BSP: all cached";

        long gapMs = Math.max(50, 1000L / Math.max(1, props.bspPerSecond()));
        Semaphore inFlight = new Semaphore(8);
        AtomicInteger done = new AtomicInteger(), found = new AtomicInteger(), failStreak = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        int i = 0;
        for (long id : ids) {
            if (failStreak.get() >= 25) break;
            String key = bspKeys.get(i++ % bspKeys.size());
            inFlight.acquire();
            fs.add(exec.submit(() -> {
                try {
                    var p = bsp.predict(key, id);
                    if (p.isEmpty()) {
                        failStreak.incrementAndGet();
                    } else {
                        failStreak.set(0);
                        var pr = p.get();
                        // failures (result 0/4) are cached as "no prediction" so we don't re-ask every night
                        data.saveBsp(id, pr.usable() ? pr.tbs() : 0, pr.score(), pr.result());
                        if (pr.usable()) found.incrementAndGet();
                    }
                } finally {
                    inFlight.release();
                    int d = done.incrementAndGet();
                    if (d % 25 == 0 || d == total) runs.progress(runId, "bsp", d, total);
                }
                return null;
            }));
            Thread.sleep(gapMs);
        }
        waitAll(fs);
        String msg = "BSP " + found.get() + "/" + total + " predicted";
        return failStreak.get() >= 25 ? msg + " (BSP stopped answering — rest kept from cache)" : msg;
    }

    /** Re-checks each key's BSP subscription at most every 12 hours. */
    public void refreshBspSubscriptions() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(12);
        for (KeyRepository.StoredKey k : keys.healthy()) {
            if (k.bspCheckedAt() != null && k.bspCheckedAt().isAfter(cutoff)) continue;
            bsp.subscription(k.key()).ifPresent(s -> keys.setBsp(k.id(), s.active(), s.expiresAt()));
        }
    }

    // ------------------------------------------------------------------ Torn call with retries

    /** One Torn call through the shared pool, retrying per error type. Throws on fatal errors. */
    public JsonNode call(String path) throws InterruptedException {
        TornApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            KeyPool.Leased k = pool.acquire();
            try {
                return torn.get(path, k.key());
            } catch (TornApiException e) {
                last = e;
                switch (e.kind()) {
                    case KEY -> pool.markBad(k, "Torn error " + e.code() + ": " + e.getMessage());
                    case RATE -> { pool.saturate(k); Thread.sleep(5000); }
                    case FATAL -> throw e;
                    case RETRY -> Thread.sleep(1500L * attempt);
                }
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------ helpers

    private static Long longOrNull(JsonNode n) {
        return n.isNumber() && n.asLong() > 0 ? n.asLong() : null;
    }

    private static void waitAll(List<Future<?>> fs) throws InterruptedException {
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.warn("Task failed", e.getCause());
            }
        }
    }
}
