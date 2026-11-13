package com.macmario.services.comm.teams.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Acquires app-only tokens via the OAuth2 client-credentials grant and caches
 * them until shortly before expiry.
 *
 * <p>App-only tokens are suitable for <em>reading</em> Teams messages and
 * managing change-notification subscriptions. They cannot send normal channel or
 * chat messages (Graph permits app-only sends only for migration) — use a
 * delegated token via {@link StaticTokenProvider} for sending.
 *
 * <p>Thread-safe: token refresh is guarded so concurrent callers make at most one
 * outstanding token request.
 */
public final class ClientCredentialTokenProvider implements AccessTokenProvider {

    /** Refresh this long before the token's stated expiry to avoid edge-of-expiry races. */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final HttpTransport transport;
    private final TeamsConfig config;
    private final Clock clock;
    private final Object lock = new Object();

    private volatile AccessToken cached;

    public ClientCredentialTokenProvider(HttpTransport transport, TeamsConfig config) {
        this(transport, config, Clock.systemUTC());
    }

    public ClientCredentialTokenProvider(HttpTransport transport, TeamsConfig config, Clock clock) {
        this.transport = transport;
        this.config = config.requireClientCredentials();
        this.clock = clock;
    }

    @Override
    public String token() {
        var local = cached;
        var now = clock.instant();
        if (local != null && local.isValid(now, EXPIRY_SKEW)) {
            return local.value();
        }
        synchronized (lock) {
            now = clock.instant();
            if (cached != null && cached.isValid(now, EXPIRY_SKEW)) {
                return cached.value();
            }
            cached = fetch(now);
            return cached.value();
        }
    }

    private AccessToken fetch(java.time.Instant now) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());
        form.put("scope", config.scope());

        JsonNode response = transport.postForm(URI.create(config.tokenEndpoint()), form);
        var accessToken = text(response, "access_token");
        if (accessToken == null) {
            throw new TeamsException("Token endpoint returned no access_token");
        }
        long expiresIn = response.path("expires_in").asLong(3600L);
        return AccessToken.expiringIn(accessToken, expiresIn, now);
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
