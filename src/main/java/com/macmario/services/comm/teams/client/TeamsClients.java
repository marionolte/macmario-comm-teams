package com.macmario.services.comm.teams.client;

import com.macmario.services.comm.teams.auth.AccessTokenProvider;
import com.macmario.services.comm.teams.auth.AuthorizationCodeFlow;
import com.macmario.services.comm.teams.auth.ClientCredentialTokenProvider;
import com.macmario.services.comm.teams.auth.GraphScopes;
import com.macmario.services.comm.teams.auth.RefreshingTokenProvider;
import com.macmario.services.comm.teams.auth.StaticTokenProvider;
import com.macmario.services.comm.teams.auth.TokenSet;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Factory methods for building {@link TeamsClient} and {@link WebhookNotifier}
 * instances. This is the recommended entry point for a host application.
 *
 * <p>Each factory shares a single {@link HttpTransport} internally (one pooled
 * {@link java.net.http.HttpClient}). To share one transport across several
 * clients, build it once with {@link HttpTransport#createDefault()} and use the
 * {@code *With} overloads.
 */
public final class TeamsClients {

    private TeamsClients() {
    }

    /**
     * Graph client that sends as the signed-in user. Supply a delegated token
     * source (e.g. the host application's current access token). This is the
     * required setup for sending channel and chat messages.
     */
    public static TeamsClient graphWithUserToken(TeamsConfig config, Supplier<String> delegatedToken) {
        return graphWith(HttpTransport.createDefault(), config, new StaticTokenProvider(delegatedToken));
    }

    /**
     * Graph client using an app-only (client-credentials) token. Suitable for
     * reading messages and managing subscriptions; it cannot send normal messages.
     */
    public static TeamsClient graphWithAppToken(TeamsConfig config) {
        var transport = HttpTransport.createDefault();
        var tokens = new ClientCredentialTokenProvider(transport, config);
        return graphWith(transport, config, tokens);
    }

    /** Graph client with a fully custom token provider and shared transport. */
    public static TeamsClient graphWith(HttpTransport transport, TeamsConfig config,
            AccessTokenProvider tokenProvider) {
        Objects.requireNonNull(config, "config");
        return new GraphTeamsClient(transport, tokenProvider, config.graphBaseUrl());
    }

    /**
     * Starts an interactive user login (OAuth2 authorization code + PKCE). Call
     * {@code start()} to get the sign-in URL, then {@code exchange(...)} on the
     * callback to obtain a {@code TokenSet}; feed that to {@link #graphAfterLogin}.
     */
    public static AuthorizationCodeFlow login(TeamsConfig config) {
        return login(HttpTransport.createDefault(), config);
    }

    /** As {@link #login(TeamsConfig)} but reuses a shared transport. */
    public static AuthorizationCodeFlow login(HttpTransport transport, TeamsConfig config) {
        return new AuthorizationCodeFlow(transport, config);
    }

    /**
     * Graph client for a user who just logged in. Holds that user's {@code TokenSet}
     * and refreshes it automatically; {@code onRefresh} receives the rotated token set
     * so the host can persist it. <strong>Per user</strong> — keep in that user's
     * session, never share across users.
     */
    public static TeamsClient graphAfterLogin(TeamsConfig config, TokenSet tokenSet,
            Consumer<TokenSet> onRefresh) {
        return graphAfterLogin(HttpTransport.createDefault(), config, tokenSet, onRefresh);
    }

    /**
     * As {@link #graphAfterLogin(TeamsConfig, TokenSet, Consumer)} but reuses a shared
     * transport — build one {@link HttpTransport} at app startup and pass it per request
     * to avoid creating an {@link java.net.http.HttpClient} on every call.
     */
    public static TeamsClient graphAfterLogin(HttpTransport transport, TeamsConfig config,
            TokenSet tokenSet, Consumer<TokenSet> onRefresh) {
        return graphFromTokenSet(transport, config, tokenSet, config.clientSecret(), onRefresh);
    }

    /**
     * Graph client for a user signed in via the <em>device-code</em> flow (a public
     * client): refresh never sends a {@code client_secret}, even if one is configured
     * for other uses. Sending it would fail with {@code AADSTS700025}.
     */
    public static TeamsClient graphAfterDeviceLogin(HttpTransport transport, TeamsConfig config,
            TokenSet tokenSet, Consumer<TokenSet> onRefresh) {
        return graphFromTokenSet(transport, config, tokenSet, null, onRefresh);
    }

    private static TeamsClient graphFromTokenSet(HttpTransport transport, TeamsConfig config,
            TokenSet tokenSet, String clientSecret, Consumer<TokenSet> onRefresh) {
        Objects.requireNonNull(config, "config");
        var provider = new RefreshingTokenProvider(
                transport, config.tokenEndpoint(), config.clientId(),
                clientSecret, GraphScopes.resolve(config.scope()),
                tokenSet, Clock.systemUTC(), onRefresh);
        return new GraphTeamsClient(transport, provider, config.graphBaseUrl());
    }

    /** Directory for discovering teams/channels/chats and resolving names to IDs. */
    public static TeamsDirectory directory(HttpTransport transport, TeamsConfig config,
            AccessTokenProvider tokenProvider) {
        Objects.requireNonNull(config, "config");
        return new TeamsDirectory(transport, tokenProvider, config.graphBaseUrl());
    }

    /** Webhook notifier using the webhook URL from {@code config}. */
    public static WebhookNotifier webhook(TeamsConfig config) {
        return new WebhookNotifier(HttpTransport.createDefault(), config.requireWebhookUrl());
    }

    /** Webhook notifier using an explicit URL and a shared transport. */
    public static WebhookNotifier webhook(HttpTransport transport, String webhookUrl) {
        return new WebhookNotifier(transport, webhookUrl);
    }
}
