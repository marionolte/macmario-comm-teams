package com.macmario.services.comm.teams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.support.MutableClock;
import com.macmario.services.comm.teams.support.StubServer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordTokenProviderTest {

    private StubServer server;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
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
                .username("user")
                .password("pw")
                .clientSecret(secret)
                .build();
    }

    @Test
    @DisplayName("acquires a token with the password grant; public client sends no secret")
    void acquire_publicClient() {
        server.respond(r -> StubServer.Response.json(
                "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}"));

        var provider = new PasswordTokenProvider(HttpTransport.createDefault(), config(null), clock);
        assertEquals("at", provider.token());

        var body = server.lastRequest().body();
        assertEquals("/tid/oauth2/v2.0/token", server.lastRequest().path());
        assertTrue(body.contains("grant_type=password"));
        assertTrue(body.contains("username=user"));
        assertTrue(body.contains("password=pw"));
        assertFalse(body.contains("client_secret="), "public client sends no secret");
    }

    @Test
    @DisplayName("confidential client includes the client secret")
    void acquire_confidentialClient() {
        server.respond(r -> StubServer.Response.json("{\"access_token\":\"at\",\"expires_in\":3600}"));
        new PasswordTokenProvider(HttpTransport.createDefault(), config("s3cret"), clock).token();
        assertTrue(server.lastRequest().body().contains("client_secret=s3cret"));
    }

    @Test
    @DisplayName("caches the token, then refreshes after expiry")
    void caches_thenRefreshes() {
        var provider = new PasswordTokenProvider(HttpTransport.createDefault(), config(null), clock);
        server.respond(r -> {
            if (r.body().contains("grant_type=password")) {
                return StubServer.Response.json(
                        "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}");
            }
            return StubServer.Response.json("{\"access_token\":\"at-2\",\"expires_in\":3600}");
        });

        assertEquals("at-1", provider.token());
        assertEquals("at-1", provider.token()); // cached
        clock.advance(Duration.ofSeconds(3600));
        assertEquals("at-2", provider.token()); // refreshed
        assertTrue(server.lastRequest().body().contains("grant_type=refresh_token"));
    }

    @Test
    @DisplayName("surfaces the Entra error and description on failure")
    void error_surfacesDescription() {
        server.respond(r -> new StubServer.Response(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS50126: Invalid credentials\"}"));
        var provider = new PasswordTokenProvider(HttpTransport.createDefault(), config(null), clock);

        var ex = assertThrows(TeamsException.class, provider::token);
        assertTrue(ex.getMessage().contains("invalid_grant"));
        assertTrue(ex.getMessage().contains("AADSTS50126"));
    }
}
