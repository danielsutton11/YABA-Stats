package com.dsuttz.yaba.bsp;

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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Battle Stats Predictor (lol-manager.com) — the same endpoints the BSP userscript and the Merc Bot use:
 *   GET {base}/user/{key}/{version}        -> SubscriptionActive, SubscriptionEnd
 *   GET {base}/{key}/{targetId}/{version}  -> TBS, Score, Result (0 and 4 are failures)
 */
@Component
public class BspClient {

    public record Subscription(boolean active, OffsetDateTime expiresAt) { }

    public record Prediction(long tbs, long score, int result) {
        public boolean usable() {
            return result != 0 && result != 4 && tbs > 0;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final YabaProperties props;

    public BspClient(ObjectMapper mapper, YabaProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public Optional<Subscription> subscription(String key) {
        return fetch(props.bspBase() + "/user/" + enc(key) + "/" + enc(props.bspVersion())).map(j -> new Subscription(
                j.path("SubscriptionActive").asBoolean(false), parseDate(j.path("SubscriptionEnd").asText(null))));
    }

    /** Empty = BSP didn't answer (network / HTTP error), so the caller can retry later. */
    public Optional<Prediction> predict(String key, long targetId) {
        return fetch(props.bspBase() + "/" + enc(key) + "/" + targetId + "/" + enc(props.bspVersion())).map(j ->
                new Prediction(j.path("TBS").asLong(0), j.path("Score").asLong(0), j.path("Result").asInt(0)));
    }

    private Optional<JsonNode> fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2 || res.body() == null || res.body().isBlank()) return Optional.empty();
            return Optional.of(mapper.readTree(res.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** BSP returns dates with or without a zone; values without one are UTC. */
    static OffsetDateTime parseDate(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return OffsetDateTime.parse(v);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(v).atOffset(ZoneOffset.UTC);
            } catch (Exception alsoIgnored) {
                return null;
            }
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
