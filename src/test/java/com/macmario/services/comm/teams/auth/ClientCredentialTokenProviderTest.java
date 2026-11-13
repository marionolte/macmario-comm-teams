package com.macmario.services.comm.teams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.support.MutableClock;
import com.macmario.services.comm.teams.support.StubServer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientCredentialTokenProviderTest {

    private StubServer server;
    private TeamsConfig config;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        config = TeamsConfig.builder()
                .authority(server.baseUrl())
                .tenantId("tid")
                .clientId("cid")
                .clientSecret("secret")
                .build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("caches the token and only refetches after expiry")
    void caches_untilExpiry() {
        var counter = new AtomicInteger();
        server.respond(req -> StubServer.Response.json(
                "{\"access_token\":\"tok-" + counter.incrementAndGet() + "\",\"expires_in\":3600}"));
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var provider = new ClientCredentialTokenProvider(HttpTransport.createDefault(), config, clock);

        assertEquals("tok-1", provider.token());
        assertEquals("tok-1", provider.token()); // served from cache
        assertEquals(1, server.requestCount());

        clock.advance(Duration.ofSeconds(3600));
        assertEquals("tok-2", provider.token()); // refetched after expiry
        assertEquals(2, server.requestCount());
    }

    @Test
    @DisplayName("sends client_credentials grant to the tenant token endpoint")
    void posts_clientCredentialsGrant() {
        server.respond(req -> StubServer.Response.json("{\"access_token\":\"abc\",\"expires_in\":3600}"));
        new ClientCredentialTokenProvider(HttpTransport.createDefault(), config).token();

        var request = server.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/tid/oauth2/v2.0/token", request.path());
        assertTrue(request.body().contains("grant_type=client_credentials"));
        assertTrue(request.body().contains("client_id=cid"));
    }

    @Test
    @DisplayName("throws when the token endpoint returns an error")
    void errorResponse_throws() {
        server.respond(req -> new StubServer.Response(401, "{\"error\":\"invalid_client\"}"));
        var provider = new ClientCredentialTokenProvider(HttpTransport.createDefault(), config);
        assertThrows(TeamsException.class, provider::token);
    }
}
