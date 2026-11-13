package com.macmario.services.comm.teams.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.auth.StaticTokenProvider;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.support.MutableClock;
import com.macmario.services.comm.teams.support.StubServer;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubscriptionManagerTest {

    private StubServer server;
    private SubscriptionManager manager;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        manager = new SubscriptionManager(
                HttpTransport.createDefault(),
                new StaticTokenProvider("test-token"),
                server.baseUrl(),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("create POSTs a subscription with a sub-hour expiry and returns id+expiry")
    void create_postsSubscription() {
        server.respond(req -> StubServer.Response.json(
                "{\"id\":\"sub-1\",\"resource\":\"/chats/c/messages\","
                        + "\"expirationDateTime\":\"2026-01-01T00:55:00Z\"}"));

        var sub = manager.create("/chats/c/messages", "https://app.example/hook", "s3cret");

        assertEquals("sub-1", sub.id());
        assertEquals(Instant.parse("2026-01-01T00:55:00Z"), sub.expiration());

        var request = server.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/subscriptions", request.path());
        assertEquals("Bearer test-token", request.header("Authorization"));
        assertTrue(request.body().contains("\"changeType\":\"created\""));
        assertTrue(request.body().contains("https://app.example/hook"));
        assertTrue(request.body().contains("\"clientState\":\"s3cret\""));
        assertTrue(request.body().contains("2026-01-01T00:55:00Z")); // now + 55m default
    }

    @Test
    @DisplayName("renew PATCHes the subscription with a new expiry")
    void renew_patchesSubscription() {
        server.respond(req -> StubServer.Response.json(
                "{\"id\":\"sub-1\",\"expirationDateTime\":\"2026-01-01T00:55:00Z\"}"));

        manager.renew("sub-1");

        var request = server.lastRequest();
        assertEquals("PATCH", request.method());
        assertEquals("/subscriptions/sub-1", request.path());
        assertTrue(request.body().contains("expirationDateTime"));
    }

    @Test
    @DisplayName("delete DELETEs the subscription by id")
    void delete_removesSubscription() {
        server.respond(req -> StubServer.Response.status(204));
        manager.delete("sub-1");
        assertEquals("DELETE", server.lastRequest().method());
        assertEquals("/subscriptions/sub-1", server.lastRequest().path());
    }
}
