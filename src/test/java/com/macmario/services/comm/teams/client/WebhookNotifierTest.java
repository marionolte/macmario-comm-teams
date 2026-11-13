package com.macmario.services.comm.teams.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.model.AdaptiveCard;
import com.macmario.services.comm.teams.support.StubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebhookNotifierTest {

    private StubServer server;
    private WebhookNotifier notifier;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        notifier = new WebhookNotifier(HttpTransport.createDefault(), server.baseUrl() + "/webhook");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("sends the Adaptive Card envelope and accepts HTTP 202 (Workflows)")
    void sendCard_accepts202() {
        server.respond(req -> StubServer.Response.status(202));

        notifier.send(AdaptiveCard.of("Title", "Body"));

        var request = server.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/webhook", request.path());
        assertEquals("application/json", request.header("Content-Type"));
        assertTrue(request.body().contains("\"type\":\"message\""));
        assertTrue(request.body().contains("vnd.microsoft.card.adaptive"));
    }

    @Test
    @DisplayName("plain-text send wraps the text in a card")
    void sendText_wraps() {
        server.respond(req -> StubServer.Response.status(200));
        notifier.send("deployment finished");
        assertTrue(server.lastRequest().body().contains("deployment finished"));
    }

    @Test
    @DisplayName("non-2xx webhook responses raise TeamsException")
    void errorStatus_throws() {
        server.respond(req -> new StubServer.Response(400, "Bad payload"));
        var ex = assertThrows(TeamsException.class, () -> notifier.send("x"));
        assertEquals(400, ex.status());
    }

    @Test
    @DisplayName("blank text is rejected")
    void blankText_rejected() {
        assertThrows(IllegalArgumentException.class, () -> notifier.send("   "));
    }
}
