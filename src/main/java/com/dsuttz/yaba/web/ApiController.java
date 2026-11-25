package com.dsuttz.yaba.web;

import com.dsuttz.yaba.collect.CollectorService;
import com.dsuttz.yaba.report.ReportService;
import com.dsuttz.yaba.store.DataRepository;
import com.dsuttz.yaba.store.KeyRepository;
import com.dsuttz.yaba.store.RunRepository;
import com.dsuttz.yaba.torn.KeyPool;
import com.dsuttz.yaba.torn.TornApiException;
import com.dsuttz.yaba.torn.TornClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** API for the Google Sheet. All /api/** calls need X-Api-Key (see ApiKeyFilter). */
@RestController
public class ApiController {

    private final CollectorService collector;
    private final ReportService reports;
    private final KeyRepository keys;
    private final DataRepository data;
    private final RunRepository runs;
    private final TornClient torn;
    private final KeyPool pool;

    public ApiController(CollectorService collector, ReportService reports, KeyRepository keys, DataRepository data,
                         RunRepository runs, TornClient torn, KeyPool pool) {
        this.collector = collector;
        this.reports = reports;
        this.keys = keys;
        this.data = data;
        this.runs = runs;
        this.torn = torn;
        this.pool = pool;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ status

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        List<KeyRepository.StoredKey> all = keys.all();
        long players = all.stream().filter(KeyRepository.StoredKey::healthy)
                .map(k -> k.ownerTornId() == null ? -k.id() : k.ownerTornId()).distinct().count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", runs.latestCompleteYaba().map(r -> String.valueOf(r.finishedAt())).orElse(null));
        out.put("lastYabaRun", runs.latest("yaba").orElse(null));
        out.put("running", runs.running());
        out.put("keys", Map.of("total", all.size(),
                "healthy", all.stream().filter(KeyRepository.StoredKey::healthy).count(),
                "players", players,
                "ffsActive", keys.ffsActive().size()));
        out.put("yabaFactions", data.yabaFactions().size());
        return out;
    }

    // ------------------------------------------------------------------ keys

    public record KeysRequest(List<String> keys) { }

    /** Adds keys to the saved ones (never replaces). Each key is checked against Torn and FFScouter. */
    @PostMapping("/api/keys")
    public Map<String, Object> addKeys(@RequestBody KeysRequest req) {
        List<Map<String, Object>> report = new ArrayList<>();
        int added = 0, already = 0;
        Set<String> seen = new HashSet<>();
        for (String raw : Optional.ofNullable(req.keys()).orElse(List.of())) {
            String k = raw == null ? "" : raw.trim();
            if (k.length() != 16 || !seen.add(k)) continue;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("key", mask(k));
            boolean existed = keys.find(k).isPresent();
            try {
                JsonNode info = torn.get("/key/info", k);
                long owner = info.path("info").path("user").path("id").asLong();
                keys.upsertValid(k, owner);
                line.put("owner", owner);
                line.put("status", existed ? "already saved" : "added");
                if (existed) already++; else added++;
                keys.find(k).ifPresent(sk -> {
                    collector.checkFfsKey(sk);
                    keys.find(k).ifPresent(after -> line.put("ffs", after.ffsActive() ? "registered" : "not registered"));
                });
            } catch (TornApiException e) {
                line.put("status", "rejected: " + e.getMessage());
            }
            report.add(line);
        }
        pool.reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("alreadySaved", already);
        out.put("results", report);
        out.putAll(keySummary());
        return out;
    }

    @GetMapping("/api/keys")
    public Map<String, Object> listKeys() {
        collector.refreshFfsKeys();
        Map<String, Object> out = new LinkedHashMap<>(keySummary());
        out.put("keys", keys.all().stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", mask(k.key()));
            m.put("owner", k.ownerTornId());
            m.put("healthy", k.healthy());
            m.put("error", k.lastError());
            m.put("ffs", k.ffsActive() ? "registered" : "not registered" + (k.ffsError() != null ? " (" + k.ffsError() + ")" : ""));
            return m;
        }).toList());
        return out;
    }

    private Map<String, Object> keySummary() {
        List<KeyRepository.StoredKey> all = keys.all();
        long players = all.stream().filter(KeyRepository.StoredKey::healthy)
                .map(k -> k.ownerTornId() == null ? -k.id() : k.ownerTornId()).distinct().count();
        return Map.of("total", all.size(), "players", players, "ffsActive", keys.ffsActive().size());
    }

    // ------------------------------------------------------------------ factions

    @PutMapping("/api/factions")
    public Map<String, Object> setFactions(@RequestBody List<DataRepository.FactionRef> list) {
        data.setYabaList(list);
        return Map.of("count", list.size());
    }

    // ------------------------------------------------------------------ runs + reports

    @PostMapping("/api/runs/yaba")
    public RunRepository.Run runYaba(@RequestParam(defaultValue = "false") boolean rosterOnly) {
        return collector.startYaba(rosterOnly);
    }

    public record EloRequest(List<Long> factionIds) { }

    /** Fetch current Elo now for these factions' members (e.g. the top 10 ranked factions). */
    @PostMapping("/api/runs/elo")
    public RunRepository.Run runElo(@RequestBody EloRequest req) {
        return collector.startElo(req.factionIds());
    }

    @GetMapping("/api/runs/{id}")
    public ResponseEntity<RunRepository.Run> run(@PathVariable long id) {
        return ResponseEntity.of(runs.find(id));
    }

    @GetMapping("/api/report")
    public ReportService.Report report() {
        return reports.yaba();
    }

    // ------------------------------------------------------------------ prospects

    @GetMapping("/api/lookup")
    public Map<String, Object> lookup(@RequestParam String q) throws InterruptedException {
        String query = q.trim();
        try {
            if (query.matches("\\d+")) {
                JsonNode j = collector.call("/faction/" + query + "/basic");
                JsonNode b = j.has("basic") ? j.get("basic") : j;
                return Map.of("matches", List.of(Map.of("id", b.path("id").asLong(Long.parseLong(query)),
                        "name", b.path("name").asText(), "members", b.path("members").asInt())));
            }
            JsonNode j = collector.call("/faction/search?limit=20&name=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode f : j.path("search")) {
                list.add(Map.of("id", f.path("id").asLong(), "name", f.path("name").asText(),
                        "members", f.path("members").asInt()));
            }
            return Map.of("matches", list);
        } catch (TornApiException e) {
            return Map.of("matches", List.of(), "error", e.getMessage());
        }
    }

    public record ProspectRequest(long id, String type) { }

    @PostMapping("/api/prospects")
    public RunRepository.Run startProspect(@RequestBody ProspectRequest req) {
        return collector.startProspect(req.id(), req.type());
    }

    @GetMapping("/api/prospects/{id}")
    public ResponseEntity<ReportService.ProspectReport> prospect(@PathVariable long id) {
        return ResponseEntity.of(reports.prospect(id));
    }

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private static String mask(String k) {
        return k.substring(0, 4) + "…";
    }
}
