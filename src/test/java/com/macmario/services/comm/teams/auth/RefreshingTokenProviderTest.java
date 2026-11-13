package com.macmario.services.comm.teams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.support.MutableClock;
import com.macmario.services.comm.teams.support.StubServer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshingTokenProviderTest {

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

    private RefreshingTokenProvider provider(TokenSet initial, String secret,
            java.util.function.Consumer<TokenSet> onRefresh) {
        return new RefreshingTokenProvider(
                HttpTransport.createDefault(), server.baseUrl() + "/token", "cid",
                secret, "https://graph.microsoft.com/.default offline_access",
                initial, clock, onRefresh);
    }

    @Test
    @DisplayName("returns the cached token without any HTTP call while valid")
    void cached_noHttp() {
        var initial = TokenSet.expiringIn("at-0", "rt-0", 3600, clock.instant());
        var provider = provider(initial, null, null);

        assertEquals("at-0", provider.token());
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("refreshes after expiry, rotates the refresh token, and notifies onRefresh")
    void refresh_rotatesAndNotifies() {
        server.respond(r -> StubServer.Response.json(
                "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}"));
        var rotated = new AtomicReference<TokenSet>();
        var initial = TokenSet.expiringIn("at-0", "rt-0", 3600, clock.instant());
        var provider = provider(initial, null, rotated::set);

        clock.advance(Duration.ofSeconds(3600));
        assertEquals("at-1", provider.token());

        var body = server.lastRequest().body();
        assertTrue(body.contains("grant_type=refresh_token"));
        assertTrue(body.contains("refresh_token=rt-0"));
        assertEquals("rt-1", rotated.get().refreshToken());
    }

    @Test
    @DisplayName("sends client_secret only when configured (confidential client)")
    void refresh_confidentialSendsSecret() {
        server.respond(r -> StubServer.Response.json(
                "{\"access_token\":\"at-1\",\"expires_in\":3600}"));
        var initial = TokenSet.expiringIn("at-0", "rt-0", 3600, clock.instant());
        var provider = provider(initial, "s3cret", null);

        clock.advance(Duration.ofSeconds(3600));
        provider.token();

        assertTrue(server.lastRequest().body().contains("client_secret=s3cret"));
    }

    @Test
    @DisplayName("throws when expired and no refresh token is available")
    void expired_noRefresh_throws() {
        var initial = new TokenSet("at-0", null, clock.instant().minusSeconds(10));
        var provider = provider(initial, null, null);
        assertThrows(TeamsException.class, provider::token);
    }
}
