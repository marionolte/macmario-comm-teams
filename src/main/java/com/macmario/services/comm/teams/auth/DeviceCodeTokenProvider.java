package com.macmario.services.comm.teams.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Interactive delegated sign-in via the OAuth2 device-code grant, intended for the
 * command-line {@code Main} driver (a <em>public</em> client — no client secret).
 *
 * <p>On first use it requests a device code, emits the user instructions through the
 * configured {@link Consumer}, and polls the token endpoint until the user completes
 * sign-in in a browser. Once acquired, the token (and its refresh) is handed to a
 * shared {@link RefreshingTokenProvider}, so refresh behavior is identical to the
 * authorization-code flow.
 */
public final class DeviceCodeTokenProvider implements AccessTokenProvider {

    private static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    /** Pauses between polls; injectable so tests avoid real sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpTransport transport;
    private final TeamsConfig config;
    private final Clock clock;
    private final Consumer<String> userPrompt;
    private final Sleeper sleeper;
    private final Object lock = new Object();

    private RefreshingTokenProvider delegate;

    public DeviceCodeTokenProvider(HttpTransport transport, TeamsConfig config) {
        this(transport, config, Clock.systemUTC(), System.out::println, DeviceCodeTokenProvider::sleepReal);
    }

    public DeviceCodeTokenProvider(HttpTransport transport, TeamsConfig config, Clock clock,
            Consumer<String> userPrompt, Sleeper sleeper) {
        this.transport = transport;
        this.config = config;
        this.clock = clock;
        this.userPrompt = userPrompt;
        this.sleeper = sleeper;
        config.requireTenant();
        if (config.clientId() == null) {
            throw new TeamsException("Missing required configuration value: clientId");
        }
    }

    @Override
    public String token() {
        synchronized (lock) {
            if (delegate == null) {
                var tokenSet = acquireInteractively();
                delegate = new RefreshingTokenProvider(
                        transport, config.tokenEndpoint(), config.clientId(),
                        null, // public client: no secret on refresh
                        GraphScopes.resolve(config.scope()), tokenSet, clock, null);
            }
            return delegate.token();
        }
    }

    private TokenSet acquireInteractively() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.clientId());
        form.put("scope", GraphScopes.resolve(config.scope()));
        JsonNode device = transport.postForm(URI.create(config.deviceCodeEndpoint()), form);

        var deviceCode = requiredText(device, "device_code");
        var message = text(device, "message");
        userPrompt.accept(message != null ? message
                : "To sign in, open " + text(device, "verification_uri")
                        + " and enter code " + text(device, "user_code"));

        long interval = device.path("interval").asLong(5L);
        long expiresIn = device.path("expires_in").asLong(900L);
        var deadline = clock.instant().plusSeconds(expiresIn);
        return pollForToken(deviceCode, Duration.ofSeconds(interval), deadline);
    }

    private TokenSet pollForToken(String deviceCode, Duration interval, Instant deadline) {
        var pollInterval = interval;
        while (clock.instant().isBefore(deadline)) {
            sleepFor(pollInterval);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", DEVICE_CODE_GRANT);
            form.put("client_id", config.clientId());
            form.put("device_code", deviceCode);

            var result = transport.postFormResult(URI.create(config.tokenEndpoint()), form);
            if (result.isSuccess()) {
                return TokenSets.from(result.body(), null, clock.instant());
            }
            var error = text(result.body(), "error");
            switch (error == null ? "" : error) {
                case "authorization_pending" -> { /* keep polling */ }
                case "slow_down" -> pollInterval = pollInterval.plusSeconds(5);
                case "expired_token", "authorization_declined", "access_denied" ->
                        throw new TeamsException("Device-code sign-in failed: " + error, result.status());
                default -> throw new TeamsException(
                        "Device-code token request failed: "
                                + (error == null ? "HTTP " + result.status() : error),
                        result.status());
            }
        }
        throw new TeamsException("Device-code sign-in timed out before completion");
    }

    private void sleepFor(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TeamsException("Device-code polling interrupted", e);
        }
    }

    private static void sleepReal(Duration duration) throws InterruptedException {
        Thread.sleep(Math.max(0L, duration.toMillis()));
    }

    private static String requiredText(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null) {
            throw new TeamsException("Response missing expected field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
