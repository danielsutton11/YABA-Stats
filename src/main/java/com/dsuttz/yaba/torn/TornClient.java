package com.dsuttz.yaba.torn;

import com.dsuttz.yaba.config.YabaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class TornClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final YabaProperties props;

    public TornClient(ObjectMapper mapper, YabaProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /** GET {tornBase}{path} with the key in the Authorization header. Throws TornApiException on any failure. */
    public JsonNode get(String path, String key) {
        String url = props.tornBase() + path + (path.contains("?") ? "&" : "?") + "comment=" + props.comment();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "ApiKey " + key)
                .GET().build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TornApiException(-1, "interrupted");
        } catch (Exception e) {
            throw new TornApiException(-1, "network: " + e.getMessage());
        }
        JsonNode json;
        try {
            json = mapper.readTree(res.body());
        } catch (Exception e) {
            throw new TornApiException(-1, "HTTP " + res.statusCode() + " (bad JSON)");
        }
        JsonNode err = json.path("error");
        if (err.isObject()) {
            throw new TornApiException(err.path("code").asInt(-1), err.path("error").asText("error"));
        }
        if (res.statusCode() >= 400) {
            throw new TornApiException(-1, "HTTP " + res.statusCode());
        }
        return json;
    }
}
