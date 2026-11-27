package com.dsuttz.yaba.collect;

import com.dsuttz.yaba.ffs.FfsClient;
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
 * Runs collections: rosters → personal stats (now / 30d / 90d, plus current Elo) → FFScouter estimates.
 * An "elo" run fetches only current Elo for chosen factions.
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
    private final FfsClient ffs;
    private final KeyPool pool;
    private final KeyRepository keys;
    private final DataRepository data;
    private final RunRepository runs;
    private final YabaProperties props;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    private Long yabaRunId = null;
    private final Map<Long, Long> prospectRuns = new ConcurrentHashMap<>();

    public CollectorService(TornClient torn, FfsClient ffs, KeyPool pool, KeyRepository keys, DataRepository data,
                            RunRepository runs, YabaProperties props) {
        this.torn = torn;
        this.ffs = ffs;
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

    /** Fetches only current Elo for the given factions' members (no roster, stats or estimates). */
    public synchronized RunRepository.Run startElo(List<Long> factionIds) {
        if (factionIds == null || factionIds.isEmpty()) throw new IllegalStateException("No factions given");
        if (keys.healthy().isEmpty()) throw new IllegalStateException("No healthy Torn API keys — add keys from the sheet");
        long id = runs.start("elo", null, null, false);
        exec.submit(() -> {
            try {
                pool.reload();
                runs.finish(id, "done", eloOnly(id, factionIds));
            } catch (Exception e) {
                log.error("Elo run {} failed", id, e);
                runs.finish(id, "failed", "FAILED: " + e.getMessage());
            }
        });
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
                notes.add(ffsPhase(runId, factionIds));
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
                    Integer territories = null;   // optional: a failure here doesn't fail the roster
                    try {
                        territories = territoryCount(call("/faction/" + fid + "/territory"));
                    } catch (TornApiException e) {
                        log.warn("Territories for faction {}: API error {} {}", fid, e.code(), e.getMessage());
                    }
                    data.saveRoster(fid, b.path("name").asText(null), longOrNull(b.path("leader_id")),
                            longOrNull(b.path("co_leader_id")), b.path("capacity").isNumber() ? b.get("capacity").asInt() : null,
                            rankLabel(b.path("rank")), rankLevel(b.path("rank")), territories, longOrNull(b.path("respect")),
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
        record Task(long id, Slot slot, Long ts) { }   // slot null = Elo only
        List<Task> tasks = new ArrayList<>();
        for (var n : needs) {
            if (n.now()) tasks.add(new Task(n.tornId(), Slot.NOW, null));        // also returns Elo
            else if (n.elo()) tasks.add(new Task(n.tornId(), null, null));      // cached stats, Elo missing
            if (n.d30()) tasks.add(new Task(n.tornId(), Slot.D30, ts30));
            if (n.d90()) tasks.add(new Task(n.tornId(), Slot.D90, ts90));
        }
        int total = tasks.size();
        data.clearErrors(needs.stream().filter(n -> n.now() || n.d30() || n.d90()).map(DataRepository.StatNeed::tornId).toList());
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
                    if (t.slot() == null) {                       // Elo only
                        data.saveElo(t.id(), findStat(call("/user/" + t.id() + "/personalstats?stat=elo"), "elo"));
                        return null;
                    }
                    boolean withElo = t.slot() == Slot.NOW;
                    String path = "/user/" + t.id() + "/personalstats?stat=" + STAT_LIST + (withElo ? ",elo" : "")
                            + (t.ts() != null ? "&timestamp=" + t.ts() : "");
                    JsonNode json = call(path);
                    if (withElo) data.saveElo(t.id(), findStat(json, "elo"));
                    Long[] vals = extractStats(json);
                    if (Arrays.stream(vals).allMatch(Objects::isNull)) {
                        data.saveSlotError(t.id(), t.slot(), "No personal stats returned");
                        errors.incrementAndGet();
                    } else {
                        // a stat missing from a historical snapshot means it was 0 back then
                        for (int i = 0; i < vals.length; i++) if (vals[i] == null) vals[i] = 0L;
                        data.saveSlot(t.id(), t.slot(), vals);
                    }
                } catch (TornApiException e) {
                    if (t.slot() == null) data.saveElo(t.id(), null);
                    else data.saveSlotError(t.id(), t.slot(), "API " + e.code() + ": " + e.getMessage());
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

    /** One named personal stat from a personalstats response (null if absent). */
    static Long findStat(JsonNode json, String name) {
        JsonNode root = json.has("personalstats") ? json.get("personalstats") : json;
        Deque<JsonNode> stack = new ArrayDeque<>(List.of(root));
        while (!stack.isEmpty()) {
            JsonNode n = stack.pop();
            if (n.isArray()) {
                for (JsonNode x : n) {
                    if (name.equals(x.path("name").asText(null)) && x.has("value")) return x.get("value").asLong();
                    stack.push(x);
                }
            } else if (n.isObject()) {
                JsonNode v = n.get(name);
                if (v != null && v.isNumber()) return v.asLong();
                n.forEach(c -> { if (c.isContainerNode()) stack.push(c); });
            }
        }
        return null;
    }

    /** Elo-only run: one call per member whose Elo is missing or older than the stats cache. */
    private String eloOnly(long runId, List<Long> factionIds) throws InterruptedException {
        List<Long> ids = data.eloNeeds(factionIds, props.statsCacheHours());
        int total = ids.size();
        runs.progress(runId, "elo", 0, total);
        if (total == 0) return "Elo: all up to date";
        Semaphore inFlight = new Semaphore(STAT_CONCURRENCY);
        AtomicInteger done = new AtomicInteger(), errors = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (long id : ids) {
            inFlight.acquire();
            fs.add(exec.submit(() -> {
                try {
                    data.saveElo(id, findStat(call("/user/" + id + "/personalstats?stat=elo"), "elo"));
                } catch (TornApiException e) {
                    data.saveElo(id, null);
                    errors.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.release();
                    int d = done.incrementAndGet();
                    if (d % 25 == 0 || d == total) runs.progress(runId, "elo", d, total);
                }
                return null;
            }));
        }
        waitAll(fs);
        return "Elo " + total + " members" + (errors.get() > 0 ? " (" + errors.get() + " errors)" : "");
    }

    // ------------------------------------------------------------------ phase 3: FFScouter

    private String ffsPhase(long runId, List<Long> factionIds) throws InterruptedException {
        refreshFfsKeys();
        List<String> ffsKeys = keys.ffsActive().stream().map(KeyRepository.StoredKey::key).toList();
        if (ffsKeys.isEmpty()) return "Estimates: no saved key is registered with FFScouter";
        List<Long> ids = data.estimateNeeds(factionIds, props.ffsCacheHours());
        int total = ids.size();
        runs.progress(runId, "estimates", 0, total);
        if (total == 0) return "Estimates: all cached";

        int batch = Math.max(1, props.ffsBatchSize());
        long gapMs = Math.max(100, 1000L / Math.max(1, props.ffsPerSecond()));
        int done = 0, found = 0, failStreak = 0, k = 0;
        String lastError = null;
        for (int i = 0; i < ids.size(); i += batch) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + batch));
            FfsClient.Result r = ffs.getStats(ffsKeys.get(k++ % ffsKeys.size()), chunk);
            if (!r.ok()) {
                lastError = r.error();
                if (++failStreak >= 3) break;
                i -= batch;                                   // retry this chunk with the next key
                Thread.sleep(gapMs * 3);
                continue;
            }
            failStreak = 0;
            Map<Long, FfsClient.Estimate> byId = new HashMap<>();
            r.estimates().forEach(e -> byId.put(e.playerId(), e));
            for (long id : chunk) {
                FfsClient.Estimate e = byId.get(id);
                // players FFScouter knows nothing about are cached as "no estimate" too
                data.saveEstimate(id, e == null ? null : e.bsEstimate(), e == null ? null : e.fairFight(),
                        e == null ? null : e.source(), e == null ? null : e.updated());
                if (e != null && e.bsEstimate() != null) found++;
            }
            done += chunk.size();
            runs.progress(runId, "estimates", done, total);
            Thread.sleep(gapMs);
        }
        String msg = "Estimates " + found + "/" + total + " from FFScouter";
        return failStreak >= 3 ? msg + " (FFScouter stopped answering: " + lastError + ")" : msg;
    }

    /** Re-checks which saved keys are registered with FFScouter, at most every 12 hours per key. */
    public void refreshFfsKeys() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(12);
        for (KeyRepository.StoredKey k : keys.healthy()) {
            if (k.ffsCheckedAt() != null && k.ffsCheckedAt().isAfter(cutoff)) continue;
            checkFfsKey(k);
        }
    }

    /** One FFScouter check for a key (looks up the key's own player). */
    public void checkFfsKey(KeyRepository.StoredKey k) {
        FfsClient.Result r = ffs.check(k.key(), k.ownerTornId() != null ? k.ownerTornId() : 1L);
        keys.setFfs(k.id(), r.ok(), r.ok() ? null : r.error());
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

    /**
     * Torn faction rank as shown in game, e.g. "Platinum II". Accepts the v2 object form
     * {name, division, level, ...} or a plain string; null when missing.
     */
    static String rankLabel(JsonNode r) {
        if (r == null || r.isMissingNode() || r.isNull()) return null;
        if (r.isTextual()) return r.asText().isBlank() ? null : r.asText();
        String name = r.path("name").asText("").trim();
        if (name.isEmpty()) return null;
        int div = r.path("division").asInt(0);
        String[] roman = {"", "I", "II", "III", "IV", "V"};
        return div > 0 && div < roman.length && !name.equalsIgnoreCase("Unranked") ? name + " " + roman[div] : name;
    }

    /** Territories held: v2 returns {"territory": [...]}; also accepts an id-keyed object. Null if absent. */
    static Integer territoryCount(JsonNode json) {
        JsonNode t = json == null ? null : json.path("territory");
        if (t == null || t.isMissingNode() || t.isNull()) return null;
        if (t.isArray() || t.isObject()) return t.size();
        return null;
    }

    /** Numeric rank level (higher = better rank), used to sort/colour the rank column. */
    static Integer rankLevel(JsonNode r) {
        return r != null && r.path("level").isNumber() ? r.get("level").asInt() : null;
    }

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
