package com.macmario.services.comm.teams.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * OAuth 2.0 Authorization Code flow with PKCE — the interactive user login for a
 * host web application.
 *
 * <p>Two steps, with the app holding the in-flight state between them:
 * <ol>
 *   <li>{@link #start} builds the Microsoft sign-in URL (with PKCE challenge and an
 *       anti-CSRF state) and returns an {@link AuthorizationRequest}. The app saves
 *       it in the user's session and redirects the browser to its URL.</li>
 *   <li>When the browser returns to the redirect URI with {@code code} and
 *       {@code state}, {@link #exchange} verifies the state and swaps the code for a
 *       {@link TokenSet}. Build a per-user {@link RefreshingTokenProvider} from it.</li>
 * </ol>
 *
 * <p>Confidential vs public client: if {@link TeamsConfig#clientSecret()} is set, the
 * code exchange includes it (confidential web app); otherwise it is omitted (public
 * client using PKCE only). This class is stateless and thread-safe.
 */
public final class AuthorizationCodeFlow {

    private final HttpTransport transport;
    private final TeamsConfig config;
    private final Clock clock;

    public AuthorizationCodeFlow(HttpTransport transport, TeamsConfig config) {
        this(transport, config, Clock.systemUTC());
    }

    public AuthorizationCodeFlow(HttpTransport transport, TeamsConfig config, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Starts a login using the redirect URI from config and the default delegated scopes. */
    public AuthorizationRequest start() {
        return start(config.requireRedirectUri(), GraphScopes.resolve(config.scope()));
    }

    public AuthorizationRequest start(String redirectUri) {
        return start(redirectUri, GraphScopes.resolve(config.scope()));
    }

    public AuthorizationRequest start(String redirectUri, String scope) {
        require(redirectUri, "redirectUri");
        if (config.clientId() == null) {
            throw new TeamsException("Missing required configuration value: clientId");
        }
        var pkce = Pkce.generate();
        var state = Pkce.newState();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("response_mode", "query");
        params.put("scope", scope);
        params.put("state", state);
        params.put("code_challenge", pkce.codeChallenge());
        params.put("code_challenge_method", pkce.challengeMethod());

        var url = config.authorizeEndpoint() + "?" + encodeQuery(params);
        return new AuthorizationRequest(url, state, pkce.codeVerifier(), redirectUri);
    }

    /**
     * Call this first in the callback handler: if Microsoft redirected back with an
     * {@code error} (e.g. the user declined, or an admin-consent-only scope such as
     * {@code ChannelMessage.Read.All} was requested in a tenant without consent),
     * there is no {@code code} and this surfaces the real reason. No-op when
     * {@code error} is null/blank.
     */
    public void failIfCallbackError(String error, String errorDescription) {
        if (error != null && !error.isBlank()) {
            throw new TeamsException("Login failed: " + error
                    + (errorDescription == null || errorDescription.isBlank() ? "" : ": " + errorDescription));
        }
    }

    /**
     * Completes login: verifies {@code returnedState} against the pending request,
     * then exchanges {@code code} for tokens.
     *
     * @throws TeamsException if the state does not match (no HTTP call is made) or the
     *                        token exchange fails
     */
    public TokenSet exchange(String code, String returnedState, AuthorizationRequest pending) {
        if (pending == null) {
            throw new TeamsException("No pending login (session expired or missing); restart sign-in");
        }
        require(code, "code");
        if (!statesMatch(pending.state(), returnedState)) {
            throw new TeamsException("OAuth state mismatch — possible CSRF; login rejected");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", config.clientId());
        form.put("code", code);
        form.put("redirect_uri", pending.redirectUri());
        form.put("code_verifier", pending.codeVerifier());
        form.put("scope", GraphScopes.resolve(config.scope()));
        if (config.clientSecret() != null) {
            form.put("client_secret", config.clientSecret());
        }

        JsonNode response = transport.postForm(URI.create(config.tokenEndpoint()), form);
        return TokenSets.from(response, null, clock.instant());
    }

    private static boolean statesMatch(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeQuery(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        // RFC 3986: encode spaces as %20, not '+', for query components.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new TeamsException(name + " must not be null or blank");
        }
    }
}
