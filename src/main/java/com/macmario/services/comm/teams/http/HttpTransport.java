package com.macmario.services.comm.teams.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macmario.services.comm.teams.TeamsException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Thin HTTP+JSON helper over the JDK {@link HttpClient}, shared and thread-safe.
 *
 * <p>This type is package-internal to the library's transport concerns: no
 * Jackson or {@link HttpClient} type appears in the library's public API. Both
 * the {@link HttpClient} and the {@link ObjectMapper} can be injected, which lets
 * tests point the client at a local stub server.
 */
public final class HttpTransport {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public HttpTransport(HttpClient http, ObjectMapper mapper, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    /** Creates a transport with a default {@link HttpClient} and {@link ObjectMapper}. */
    public static HttpTransport createDefault() {
        return new HttpTransport(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                new ObjectMapper(),
                DEFAULT_TIMEOUT);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public JsonNode getJson(URI uri, String bearerToken) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json").GET();
        authorize(builder, bearerToken);
        return send(builder.build(), true);
    }

    public JsonNode postJson(URI uri, String bearerToken, Object body) {
        var json = writeJson(body);
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        authorize(builder, bearerToken);
        return send(builder.build(), true);
    }

    public JsonNode patchJson(URI uri, String bearerToken, Object body) {
        var json = writeJson(body);
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        authorize(builder, bearerToken);
        return send(builder.build(), true);
    }

    public void delete(URI uri, String bearerToken) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).DELETE();
        authorize(builder, bearerToken);
        send(builder.build(), false);
    }

    /** POSTs an {@code application/x-www-form-urlencoded} body (OAuth2 token endpoints). */
    public JsonNode postForm(URI uri, Map<String, String> form) {
        return send(formRequest(uri, form), true);
    }

    /**
     * Like {@link #postForm} but returns the status and parsed body without
     * throwing on 4xx/5xx. Used by the device-code flow, where HTTP 400 with
     * {@code error=authorization_pending} is an expected polling response.
     */
    public HttpResult postFormResult(URI uri, Map<String, String> form) {
        var request = formRequest(uri, form);
        var response = execute(request);
        return new HttpResult(response.statusCode(), parseBody(response, request.uri()));
    }

    private HttpRequest formRequest(URI uri, Map<String, String> form) {
        return HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form), StandardCharsets.UTF_8))
                .build();
    }

    /** HTTP status plus parsed JSON body, for callers that handle errors themselves. */
    public record HttpResult(int status, JsonNode body) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * POSTs a raw string body and returns the HTTP status without parsing the
     * response (incoming webhooks reply with an empty or non-JSON body).
     */
    public int postRaw(URI uri, String contentType, String body) {
        var request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        var response = execute(request);
        ensureSuccess(response);
        return response.statusCode();
    }

    private JsonNode send(HttpRequest request, boolean parseBody) {
        var response = execute(request);
        ensureSuccess(response);
        return parseBody ? parseBody(response, request.uri()) : mapper.nullNode();
    }

    private JsonNode parseBody(HttpResponse<String> response, URI uri) {
        var text = response.body();
        if (text == null || text.isBlank()) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            throw new TeamsException("Failed to parse JSON response from " + uri, e);
        }
    }

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TeamsException("HTTP call failed: " + request.method() + " " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TeamsException("HTTP call interrupted: " + request.uri(), e);
        }
    }

    private static void ensureSuccess(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new TeamsException(
                    "HTTP " + code + " from " + response.uri() + ": " + snippet(response.body()),
                    code);
        }
    }

    private static void authorize(HttpRequest.Builder builder, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new TeamsException("Failed to serialize request body", e);
        }
    }

    private static String urlEncode(Map<String, String> form) {
        var sb = new StringBuilder();
        for (var entry : form.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String snippet(String body) {
        if (body == null) {
            return "<no body>";
        }
        var trimmed = body.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }
}
