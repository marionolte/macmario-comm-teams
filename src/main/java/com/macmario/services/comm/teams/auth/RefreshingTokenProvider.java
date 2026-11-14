package com.macmario.services.comm.teams.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.http.HttpTransport;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Serves a delegated access token obtained from an interactive sign-in (authorization
 * code or device code), transparently refreshing it with the refresh token before it
 * expires.
 *
 * <p><strong>Per user.</strong> Each instance holds one user's tokens — keep it in that
 * user's session and never share it across users.
 *
 * <p>Confidential vs public client: a {@code clientSecret} is sent on refresh only when
 * one is supplied (confidential web app). Public clients (PKCE / device code) must not
 * send a secret, so pass {@code null}.
 *
 * <p>Entra rotates refresh tokens on every refresh; the {@code onRefresh} callback
 * receives the new {@link TokenSet} so the host can persist it. Thread-safe: refresh is
 * guarded so concurrent callers trigger at most one refresh.
 */
public final class RefreshingTokenProvider implements AccessTokenProvider {

    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final HttpTransport transport;
    private final String tokenEndpoint;
    private final String clientId;
    private final String clientSecret; // nullable (public client)
    private final String scope;
    private final Clock clock;
    private final Consumer<TokenSet> onRefresh;
    private final Object lock = new Object();

    private TokenSet current;

    public RefreshingTokenProvider(HttpTransport transport, String tokenEndpoint, String clientId,
            String clientSecret, String scope, TokenSet initial, Clock clock, Consumer<TokenSet> onRefresh) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.current = Objects.requireNonNull(initial, "initial");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onRefresh = onRefresh == null ? t -> { } : onRefresh;
    }

    /** @return the current token set (for persistence); tokens are redacted in logs. */
    public TokenSet tokenSet() {
        synchronized (lock) {
            return current;
        }
    }

    @Override
    public String token() {
        synchronized (lock) {
            var now = clock.instant();
            if (current.isValid(now, EXPIRY_SKEW)) {
                return current.accessToken();
            }
            if (!current.hasRefreshToken()) {
                throw new TeamsException("Access token expired and no refresh token is available; "
                        + "the user must sign in again");
            }
            try {
                current = refresh(now);
            } catch (TeamsException e) {
                throw new TeamsException(
                        "Sign-in expired or revoked — sign in again (" + e.getMessage() + ")", e);
            }
            onRefresh.accept(current);
            return current.accessToken();
        }
    }

    private TokenSet refresh(Instant now) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("refresh_token", current.refreshToken());
        if (scope != null && !scope.isBlank()) {
            form.put("scope", scope);
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.put("client_secret", clientSecret);
        }
        JsonNode response = transport.postForm(URI.create(tokenEndpoint), form);
        return TokenSets.from(response, current.refreshToken(), now);
    }
}
