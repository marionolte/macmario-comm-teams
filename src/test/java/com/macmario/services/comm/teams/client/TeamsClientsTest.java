package com.macmario.services.comm.teams.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.auth.TokenSet;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.support.StubServer;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamsClientsTest {

    private StubServer server;
    private TeamsConfig config;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        // graph + token endpoints both point at the stub; a secret IS configured.
        config = TeamsConfig.builder()
                .graphBaseUrl(server.baseUrl())
                .authority(server.baseUrl())
                .tenantId("tid")
                .clientId("cid")
                .clientSecret("should-not-be-sent-on-device-refresh")
                .build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("graphAfterDeviceLogin refreshes without a client_secret even when one is configured")
    void deviceLogin_refreshOmitsSecret() {
        server.respond(req -> {
            if (req.path().endsWith("/token")) {
                return StubServer.Response.json("{\"access_token\":\"at-2\",\"expires_in\":3600}");
            }
            return StubServer.Response.json("{\"id\":\"me\",\"displayName\":\"Alice\"}");
        });

        // Already-expired token forces a refresh on the first call.
        var expired = new TokenSet("at-1", "rt-1", Instant.now().minusSeconds(10));
        var client = TeamsClients.graphAfterDeviceLogin(
                HttpTransport.createDefault(), config, expired, ts -> { });

        client.fetchCurrentUser();

        var tokenReq = server.requests().stream()
                .filter(r -> r.path().endsWith("/token"))
                .findFirst().orElseThrow();
        assertTrue(tokenReq.body().contains("grant_type=refresh_token"));
        assertFalse(tokenReq.body().contains("client_secret="),
                "device-login client is public; no secret on refresh");
    }
}
