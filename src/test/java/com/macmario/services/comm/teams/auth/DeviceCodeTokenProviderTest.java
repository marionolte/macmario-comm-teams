package com.macmario.services.comm.teams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class DeviceCodeTokenProviderTest {

    private StubServer server;
    private TeamsConfig config;
    private final StringBuilder prompt = new StringBuilder();

    @BeforeEach
    void setUp() {
        server = new StubServer();
        config = TeamsConfig.builder()
                .authority(server.baseUrl())
                .tenantId("tid")
                .clientId("cid")
                .build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("polls through authorization_pending then caches the acquired token")
    void acquiresAndCaches() {
        var tokenCalls = new AtomicInteger();
        server.respond(req -> {
            if (req.path().endsWith("/devicecode")) {
                return StubServer.Response.json(
                        "{\"device_code\":\"dc\",\"user_code\":\"ABCD\",\"interval\":0,"
                                + "\"expires_in\":900,\"verification_uri\":\"https://aka.ms/devicelogin\","
                                + "\"message\":\"Go to https://aka.ms/devicelogin and enter ABCD\"}");
            }
            // token endpoint: pending on first poll, success on the second
            if (tokenCalls.incrementAndGet() == 1) {
                return new StubServer.Response(400, "{\"error\":\"authorization_pending\"}");
            }
            return StubServer.Response.json(
                    "{\"access_token\":\"at-1\",\"expires_in\":3600,\"refresh_token\":\"rt-1\"}");
        });

        var provider = new DeviceCodeTokenProvider(
                HttpTransport.createDefault(), config,
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
                prompt::append,
                duration -> { /* no real sleep in tests */ });

        assertEquals("at-1", provider.token());
        assertTrue(prompt.toString().contains("aka.ms/devicelogin"));
        assertEquals(2, tokenCalls.get());

        // cached: no further token-endpoint calls
        assertEquals("at-1", provider.token());
        assertEquals(2, tokenCalls.get());
    }

    @Test
    @DisplayName("refreshes via the refresh token once the access token expires")
    void refreshesAfterExpiry() {
        var tokenCalls = new AtomicInteger();
        server.respond(req -> {
            if (req.path().endsWith("/devicecode")) {
                return StubServer.Response.json(
                        "{\"device_code\":\"dc\",\"interval\":0,\"expires_in\":900,"
                                + "\"message\":\"sign in\"}");
            }
            int n = tokenCalls.incrementAndGet();
            // 1: initial acquire; 2: refresh_token grant
            var token = n == 1 ? "at-1" : "at-2";
            return StubServer.Response.json(
                    "{\"access_token\":\"" + token + "\",\"expires_in\":3600,\"refresh_token\":\"rt-"
                            + n + "\"}");
        });

        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var provider = new DeviceCodeTokenProvider(
                HttpTransport.createDefault(), config, clock, prompt::append,
                duration -> { });

        assertEquals("at-1", provider.token());
        clock.advance(Duration.ofSeconds(3600));
        assertEquals("at-2", provider.token());

        var lastTokenRequest = server.requests().stream()
                .filter(r -> r.path().endsWith("/token"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(lastTokenRequest.body().contains("grant_type=refresh_token"));
        assertTrue(lastTokenRequest.body().contains("refresh_token=rt-1"));
    }
}
