package com.macmario.services.comm.teams.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delegated sign-in via the OAuth2 <em>Resource Owner Password Credentials</em> (ROPC)
 * grant — a username and password are exchanged directly for a token.
 *
 * <p>Once acquired, the token (and its refresh token) is handed to a shared
 * {@link RefreshingTokenProvider}, so refresh behaves exactly like the other flows.
 * A {@code client_secret} is sent on acquire and refresh <em>iff</em> one is configured
 * (confidential app); omit it for a public app with public-client flows enabled.
 *
 * <p><strong>ROPC limitations</strong> (Microsoft discourages this flow): it does not
 * work with MFA, Conditional Access, SSO, or federated accounts, and not with personal
 * Microsoft accounts. The authority must be a specific tenant or {@code organizations}
 * — never {@code common} or {@code consumers}. All delegated scopes must be
 * pre-consented by an admin, since ROPC cannot show a consent prompt.
 */
public final class PasswordTokenProvider implements AccessTokenProvider {

    private final HttpTransport transport;
    private final TeamsConfig config;
    private final Clock clock;
    private final Object lock = new Object();

    private RefreshingTokenProvider delegate;

    public PasswordTokenProvider(HttpTransport transport, TeamsConfig config) {
        this(transport, config, Clock.systemUTC());
    }

    public PasswordTokenProvider(HttpTransport transport, TeamsConfig config, Clock clock) {
        this.transport = transport;
        this.config = config.requireResourceOwnerCredentials();
        this.clock = clock;
    }

    @Override
    public String token() {
        synchronized (lock) {
            ensureSignedIn();
            return delegate.token();
        }
    }

    /** Signs in if needed and returns the acquired {@link TokenSet} (for persistence). */
    public TokenSet login() {
        synchronized (lock) {
            ensureSignedIn();
            return delegate.tokenSet();
        }
    }

    private void ensureSignedIn() {
        if (delegate == null) {
            var tokenSet = acquire();
            delegate = new RefreshingTokenProvider(
                    transport, config.tokenEndpoint(), config.clientId(),
                    config.clientSecret(), // send secret iff configured (ROPC rule)
                    GraphScopes.resolve(config.scope()), tokenSet, clock, null);
        }
    }

    private TokenSet acquire() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", config.clientId());
        form.put("username", config.username());
        form.put("password", config.password());
        form.put("scope", GraphScopes.resolve(config.scope()));
        if (config.clientSecret() != null) {
            form.put("client_secret", config.clientSecret());
        }

        var result = transport.postFormResult(URI.create(config.tokenEndpoint()), form);
        if (result.isSuccess()) {
            return TokenSets.from(result.body(), null, clock.instant());
        }
        // Surface Entra's error/description (names the AADSTS code) — never the form.
        var error = text(result.body(), "error");
        var description = text(result.body(), "error_description");
        throw new TeamsException(
                "Username/password sign-in failed: "
                        + (error != null ? error : "HTTP " + result.status())
                        + (description != null ? " - " + firstLine(description) : ""),
                result.status());
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        var line = (nl >= 0 ? s.substring(0, nl) : s).strip();
        int cr = line.indexOf('\r');
        return cr >= 0 ? line.substring(0, cr) : line;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
