package com.macmario.services.comm.teams.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.auth.AccessTokenProvider;
import com.macmario.services.comm.teams.http.HttpTransport;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates, renews, and deletes Microsoft Graph change-notification subscriptions
 * so a host web app can receive push notifications for Teams messages (rather than
 * polling with {@code TeamsClient.fetch*}).
 *
 * <p>Flow for a web app: expose an HTTPS endpoint, {@link #create} a subscription
 * pointing at it (Graph immediately validates the endpoint — see
 * {@link ChangeNotificationParser#validationToken}), then {@link #renew} before
 * {@link Subscription#expiration()} on a timer. Default lifetime is kept under one
 * hour so no {@code lifecycleNotificationUrl} is required.
 *
 * <p>Subscriptions for chat/channel messages typically need an admin-consented
 * application token (see {@link com.macmario.services.comm.teams.auth.ClientCredentialTokenProvider}).
 */
public final class SubscriptionManager {

    /** Kept under the 60-minute maximum so a lifecycleNotificationUrl is not required. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(55);

    private final HttpTransport transport;
    private final AccessTokenProvider tokens;
    private final String baseUrl;
    private final Clock clock;

    public SubscriptionManager(HttpTransport transport, AccessTokenProvider tokens, String graphBaseUrl) {
        this(transport, tokens, graphBaseUrl, Clock.systemUTC());
    }

    public SubscriptionManager(HttpTransport transport, AccessTokenProvider tokens, String graphBaseUrl,
            Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(graphBaseUrl, "graphBaseUrl"));
        this.clock = clock;
    }

    /**
     * Creates a subscription for {@code created} changes with the default lifetime.
     *
     * @param resource        e.g. {@code "/teams/{id}/channels/{id}/messages"} or {@code "/chats/{id}/messages"}
     * @param notificationUrl the HTTPS endpoint Graph will call back
     * @param clientState     an opaque secret echoed on every notification; verify it on receipt
     */
    public Subscription create(String resource, String notificationUrl, String clientState) {
        return create(resource, notificationUrl, clientState, DEFAULT_LIFETIME);
    }

    public Subscription create(String resource, String notificationUrl, String clientState, Duration lifetime) {
        require(resource, "resource");
        require(notificationUrl, "notificationUrl");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changeType", "created");
        body.put("notificationUrl", notificationUrl);
        body.put("resource", resource);
        body.put("expirationDateTime", clock.instant().plus(lifetime).toString());
        if (clientState != null && !clientState.isBlank()) {
            body.put("clientState", clientState);
        }
        var response = transport.postJson(uri("/subscriptions"), tokens.token(), body);
        return toSubscription(response);
    }

    /** Extends the subscription's expiry by {@code lifetime} from now. */
    public Subscription renew(String subscriptionId, Duration lifetime) {
        require(subscriptionId, "subscriptionId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expirationDateTime", clock.instant().plus(lifetime).toString());
        var response = transport.patchJson(uri("/subscriptions/" + subscriptionId), tokens.token(), body);
        return toSubscription(response);
    }

    public Subscription renew(String subscriptionId) {
        return renew(subscriptionId, DEFAULT_LIFETIME);
    }

    public void delete(String subscriptionId) {
        require(subscriptionId, "subscriptionId");
        transport.delete(uri("/subscriptions/" + subscriptionId), tokens.token());
    }

    private static Subscription toSubscription(JsonNode node) {
        return new Subscription(
                text(node, "id"),
                text(node, "resource"),
                parseInstant(text(node, "expirationDateTime")));
    }

    private URI uri(String relativePath) {
        return URI.create(baseUrl + relativePath);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new TeamsException(name + " must not be null or blank");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
