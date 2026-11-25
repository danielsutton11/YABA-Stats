package com.dsuttz.yaba.ffs;

import com.dsuttz.yaba.config.YabaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FFScouter: GET {base}/get-stats?key={Torn key registered on ffscouter.com}&targets=id1,id2,...
 * Returns one object per player with a merged top-level estimate (bs_estimate, fair_fight,
 * last_updated, source = bss | premium | spies). Premium keys get premium / spy data where it exists.
 */
@Component
public class FfsClient {

    public record Estimate(long playerId, Long bsEstimate, Double fairFight, String source, OffsetDateTime updated) { }

    /** ok = FFScouter answered with a list; otherwise error says why (e.g. key not registered). */
    public record Result(boolean ok, List<Estimate> estimates, String error) { }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final YabaProperties props;

    public FfsClient(ObjectMapper mapper, YabaProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public Result getStats(String key, Collection<Long> ids) {
        String targets = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        String url = props.ffsBase() + "/get-stats?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&targets=" + targets;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode j = mapper.readTree(res.body() == null || res.body().isBlank() ? "null" : res.body());
            if (j == null || !j.isArray()) {
                String msg = j == null ? null : firstText(j, "error", "message", "detail");
                return new Result(false, List.of(), (msg != null ? msg : "unexpected response") + " (HTTP " + res.statusCode() + ")");
            }
            List<Estimate> out = new ArrayList<>();
            for (JsonNode p : j) out.add(parse(p));
            return new Result(true, out, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, List.of(), "interrupted");
        } catch (Exception e) {
            return new Result(false, List.of(), "network: " + e.getMessage());
        }
    }

    /** A key counts as registered if FFScouter answers a one-player lookup with a list. */
    public Result check(String key, long anyPlayerId) {
        return getStats(key, List.of(anyPlayerId));
    }

    static Estimate parse(JsonNode p) {
        JsonNode bs = p.path("bs_estimate"), ff = p.path("fair_fight"), lu = p.path("last_updated");
        OffsetDateTime updated = null;
        if (lu.isNumber()) updated = Instant.ofEpochSecond(lu.asLong()).atOffset(ZoneOffset.UTC);
        else if (lu.isTextual()) {
            try { updated = OffsetDateTime.parse(lu.asText()); } catch (Exception ignored) { /* leave null */ }
        }
        return new Estimate(p.path("player_id").asLong(),
                bs.isNumber() && bs.asLong() > 0 ? bs.asLong() : null,
                ff.isNumber() ? ff.asDouble() : null,
                p.path("source").isTextual() ? p.get("source").asText() : null,
                updated);
    }

    private static String firstText(JsonNode j, String... fields) {
        for (String f : fields) {
            JsonNode v = j.path(f);
            if (v.isTextual()) return v.asText();
            if (v.isObject() && v.path("error").isTextual()) return v.path("error").asText();
        }
        return null;
    }
}
