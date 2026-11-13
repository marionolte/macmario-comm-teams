package com.macmario.services.comm.teams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.support.StubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizationCodeFlowTest {

    private static final String REDIRECT = "https://app.example/cb";

    private StubServer server;

    @BeforeEach
    void setUp() {
        server = new StubServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private TeamsConfig config(String secret) {
        return TeamsConfig.builder()
                .authority(server.baseUrl())
                .tenantId("tid")
                .clientId("cid")
                .clientSecret(secret)
                .redirectUri(REDIRECT)
                .build();
    }

    @Test
    @DisplayName("start builds an authorize URL with PKCE, state, and %20-encoded scope")
    void start_buildsAuthorizeUrl() {
        var flow = new AuthorizationCodeFlow(HttpTransport.createDefault(), config(null));

        var req = flow.start();

        assertTrue(req.authorizationUrl().startsWith(server.baseUrl() + "/tid/oauth2/v2.0/authorize?"));
        assertTrue(req.authorizationUrl().contains("client_id=cid"));
        assertTrue(req.authorizationUrl().contains("response_type=code"));
        assertTrue(req.authorizationUrl().contains("code_challenge_method=S256"));
        assertTrue(req.authorizationUrl().contains("state=" + req.state()));
        assertTrue(req.authorizationUrl().contains("redirect_uri=https%3A%2F%2Fapp.example%2Fcb"));
        assertTrue(req.authorizationUrl().contains("%20"), "scope spaces must be %20");
        assertFalse(req.authorizationUrl().contains("+"), "no '+' encoding allowed");
        assertFalse(req.codeVerifier().isBlank());
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("exchange swaps the code for tokens (public client: no secret)")
    void exchange_publicClient() {
        server.respond(r -> StubServer.Response.json(
                "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}"));
        var flow = new AuthorizationCodeFlow(HttpTransport.createDefault(), config(null));
        var pending = flow.start();

        var tokens = flow.exchange("auth-code-123", pending.state(), pending);

        assertEquals("at", tokens.accessToken());
        assertEquals("rt", tokens.refreshToken());
        var req = server.lastRequest();
        assertEquals("/tid/oauth2/v2.0/token", req.path());
        assertTrue(req.body().contains("grant_type=authorization_code"));
        assertTrue(req.body().contains("code=auth-code-123"));
        assertTrue(req.body().contains("code_verifier="));
        assertTrue(req.body().contains("redirect_uri=https%3A%2F%2Fapp.example%2Fcb"));
        assertFalse(req.body().contains("client_secret="), "public client must not send a secret");
    }

    @Test
    @DisplayName("exchange includes client_secret for a confidential client")
    void exchange_confidentialClient() {
        server.respond(r -> StubServer.Response.json(
                "{\"access_token\":\"at\",\"expires_in\":3600}"));
        var flow = new AuthorizationCodeFlow(HttpTransport.createDefault(), config("s3cret"));
        var pending = flow.start();

        flow.exchange("code", pending.state(), pending);

        assertTrue(server.lastRequest().body().contains("client_secret=s3cret"));
    }

    @Test
    @DisplayName("state mismatch is rejected before any HTTP call")
    void exchange_stateMismatch_rejected() {
        var flow = new AuthorizationCodeFlow(HttpTransport.createDefault(), config(null));
        var pending = flow.start();

        assertThrows(TeamsException.class, () -> flow.exchange("code", "tampered-state", pending));
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("failIfCallbackError surfaces an OAuth error and makes no HTTP call")
    void failIfCallbackError_surfaces() {
        var flow = new AuthorizationCodeFlow(HttpTransport.createDefault(), config(null));
        var ex = assertThrows(TeamsException.class,
                () -> flow.failIfCallbackError("consent_required", "Admin approval needed"));
        assertTrue(ex.getMessage().contains("consent_required"));
        assertEquals(0, server.requestCount());
        // no error -> no throw
        flow.failIfCallbackError(null, null);
    }

    @Test
    @DisplayName("a null pending request (expired session) throws, not an NPE")
    void exchange_nullPending_throws() {
        var flow = new AuthorizationCodeFlow(HttpTransport.createDefault(), config(null));
        assertThrows(TeamsException.class, () -> flow.exchange("code", "state", null));
        assertEquals(0, server.requestCount());
    }
}
