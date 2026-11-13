package com.macmario.services.comm.teams.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macmario.services.comm.teams.TeamsException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses Microsoft Graph change-notification callbacks for a host web app.
 *
 * <p>A web application exposes an HTTPS endpoint, registers it when creating a
 * Graph subscription, and delegates the two things Graph sends to that endpoint:
 *
 * <ol>
 *   <li><b>Subscription validation.</b> When a subscription is created or renewed,
 *       Graph calls the endpoint with a {@code validationToken} query parameter.
 *       The endpoint must echo that token back as {@code text/plain} with HTTP 200
 *       within ~10 seconds. Use {@link #validationToken(String)}.</li>
 *   <li><b>Change notifications.</b> Graph POSTs a JSON body with a {@code value}
 *       array of changes. Use {@link #parse(String)}, then verify each
 *       notification's {@code clientState} via
 *       {@link ChangeNotification#hasValidClientState(String)} before acting, and
 *       fetch the changed message by id (notifications carry no message content
 *       unless resource data is encrypted).</li>
 * </ol>
 *
 * <p>Stateless and thread-safe.
 */
public final class ChangeNotificationParser {

    private final ObjectMapper mapper;

    public ChangeNotificationParser() {
        this(new ObjectMapper());
    }

    public ChangeNotificationParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Extracts the subscription-validation token from a request's raw query string
     * (for example {@code "validationToken=abc%20123"}).
     *
     * @return the decoded token to echo back, or empty if this is not a validation
     *         request
     */
    public Optional<String> validationToken(String rawQueryString) {
        if (rawQueryString == null || rawQueryString.isBlank()) {
            return Optional.empty();
        }
        for (var pair : rawQueryString.split("&")) {
            int eq = pair.indexOf('=');
            var key = eq >= 0 ? pair.substring(0, eq) : pair;
            if ("validationToken".equals(key)) {
                var value = eq >= 0 ? pair.substring(eq + 1) : "";
                return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    /**
     * Parses the POSTed notification body into a list of {@link ChangeNotification}.
     *
     * @throws TeamsException if the body is not valid JSON
     */
    public List<ChangeNotification> parse(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(jsonBody);
        } catch (IOException e) {
            throw new TeamsException("Failed to parse change-notification body", e);
        }
        var value = root.path("value");
        if (!value.isArray()) {
            return List.of();
        }
        List<ChangeNotification> result = new ArrayList<>(value.size());
        for (JsonNode node : value) {
            result.add(toNotification(node));
        }
        return List.copyOf(result);
    }

    private static ChangeNotification toNotification(JsonNode node) {
        return new ChangeNotification(
                text(node, "subscriptionId"),
                text(node, "changeType"),
                text(node, "resource"),
                text(node.path("resourceData"), "id"),
                text(node, "clientState"),
                text(node, "tenantId"));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
