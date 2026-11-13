package com.macmario.services.comm.teams.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.auth.StaticTokenProvider;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.model.OutgoingMessage;
import com.macmario.services.comm.teams.support.StubServer;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphTeamsClientTest {

    private StubServer server;
    private TeamsClient client;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        client = new GraphTeamsClient(
                HttpTransport.createDefault(),
                new StaticTokenProvider("test-token"),
                server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("sendChannelMessage posts the body and returns the new message id")
    void sendChannelMessage_returnsId() {
        server.respond(req -> StubServer.Response.json("{\"id\":\"m1\"}"));

        var id = client.sendChannelMessage("T", "C", OutgoingMessage.html("<b>hi</b>"));

        assertEquals("m1", id);
        var request = server.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/teams/T/channels/C/messages", request.path());
        assertEquals("Bearer test-token", request.header("Authorization"));
        assertTrue(request.body().contains("\"contentType\":\"html\""));
        assertTrue(request.body().contains("<b>hi</b>"));
    }

    @Test
    @DisplayName("fetchChannelMessages parses the value array newest-first as returned")
    void fetchChannelMessages_parses() {
        server.respond(req -> StubServer.Response.json("""
            {"value":[
              {"id":"m1","createdDateTime":"2026-01-02T10:00:00Z",
               "body":{"contentType":"text","content":"hello"},
               "from":{"user":{"id":"u1","displayName":"Alice"}}},
              {"id":"m2","body":{"contentType":"html","content":"<p>hi</p>"},
               "from":{"user":{"id":"u2","displayName":"Bob"}}}
            ]}"""));

        var messages = client.fetchChannelMessages("T", "C", 5);

        assertEquals(2, messages.size());
        assertEquals("m1", messages.get(0).id());
        assertEquals("hello", messages.get(0).content());
        assertEquals("Alice", messages.get(0).fromDisplayName());
        assertEquals(Instant.parse("2026-01-02T10:00:00Z"), messages.get(0).createdDateTime());
        assertEquals(null, messages.get(1).createdDateTime()); // missing timestamp tolerated
        assertTrue(server.lastRequest().query().contains("$top=5"));
    }

    @Test
    @DisplayName("ensureOneOnOneChat resolves /me and creates a chat with both members")
    void ensureOneOnOneChat_createsChat() {
        server.respond(req -> {
            if (req.path().equals("/me")) {
                return StubServer.Response.json("{\"id\":\"me-1\"}");
            }
            return StubServer.Response.json("{\"id\":\"chat1\"}");
        });

        var chatId = client.ensureOneOnOneChat("user-2");

        assertEquals("chat1", chatId);
        var createChat = server.requests().stream()
                .filter(r -> r.path().equals("/chats") && r.method().equals("POST"))
                .findFirst().orElseThrow();
        assertTrue(createChat.body().contains("oneOnOne"));
        assertTrue(createChat.body().contains("users('me-1')"));
        assertTrue(createChat.body().contains("users('user-2')"));
    }

    @Test
    @DisplayName("sendDirectMessage creates a chat then posts the message")
    void sendDirectMessage_fullFlow() {
        server.respond(req -> {
            if (req.path().equals("/me")) {
                return StubServer.Response.json("{\"id\":\"me-1\"}");
            }
            if (req.path().equals("/chats")) {
                return StubServer.Response.json("{\"id\":\"chat1\"}");
            }
            return StubServer.Response.json("{\"id\":\"msg-9\"}");
        });

        var id = client.sendDirectMessage("user-2", OutgoingMessage.text("yo"));

        assertEquals("msg-9", id);
        assertEquals("/chats/chat1/messages", server.lastRequest().path());
    }

    @Test
    @DisplayName("fetchCurrentUser parses /me")
    void fetchCurrentUser_parsesMe() {
        server.respond(req -> StubServer.Response.json(
                "{\"id\":\"me-1\",\"displayName\":\"Alice\",\"userPrincipalName\":\"alice@contoso.com\"}"));

        var me = client.fetchCurrentUser();

        assertEquals("/me", server.lastRequest().path());
        assertEquals("me-1", me.id());
        assertEquals("Alice", me.displayName());
        assertEquals("alice@contoso.com", me.userPrincipalName());
    }

    @Test
    @DisplayName("blank identifiers are rejected before any HTTP call")
    void blankId_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> client.sendChannelMessage("", "C", OutgoingMessage.text("x")));
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("Graph error responses surface as TeamsException with status")
    void errorResponse_throwsWithStatus() {
        server.respond(req -> new StubServer.Response(403, "{\"error\":{\"code\":\"Forbidden\"}}"));
        var ex = assertThrows(TeamsException.class,
                () -> client.sendChannelMessage("T", "C", OutgoingMessage.text("x")));
        assertEquals(403, ex.status());
    }
}
