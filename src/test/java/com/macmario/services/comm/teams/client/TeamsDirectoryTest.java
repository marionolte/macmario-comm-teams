package com.macmario.services.comm.teams.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.auth.StaticTokenProvider;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.support.StubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamsDirectoryTest {

    private StubServer server;
    private TeamsDirectory dir;

    @BeforeEach
    void setUp() {
        server = new StubServer();
        dir = new TeamsDirectory(HttpTransport.createDefault(),
                new StaticTokenProvider("t"), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("resolveTeamId matches a joined team by name (case-insensitive)")
    void resolveTeamId_byName() {
        server.respond(r -> StubServer.Response.json(
                "{\"value\":[{\"id\":\"T1\",\"displayName\":\"Alpha\"},"
                        + "{\"id\":\"T2\",\"displayName\":\"Beta\"}]}"));
        assertEquals("T1", dir.resolveTeamId("alpha"));
        assertEquals("/me/joinedTeams", server.lastRequest().path());
    }

    @Test
    @DisplayName("a GUID team arg passes through without any HTTP call")
    void resolveTeamId_guidPassthrough() {
        var guid = "11111111-1111-1111-1111-111111111111";
        assertEquals(guid, dir.resolveTeamId(guid));
        assertEquals(0, server.requestCount());
    }

    @Test
    @DisplayName("unknown team name throws, listing candidates")
    void resolveTeamId_unknown_throws() {
        server.respond(r -> StubServer.Response.json(
                "{\"value\":[{\"id\":\"T1\",\"displayName\":\"Alpha\"}]}"));
        var ex = assertThrows(TeamsException.class, () -> dir.resolveTeamId("Nope"));
        assertEquals(true, ex.getMessage().contains("Alpha"));
    }

    @Test
    @DisplayName("ambiguous team name throws")
    void resolveTeamId_ambiguous_throws() {
        server.respond(r -> StubServer.Response.json(
                "{\"value\":[{\"id\":\"T1\",\"displayName\":\"Dup\"},"
                        + "{\"id\":\"T2\",\"displayName\":\"Dup\"}]}"));
        assertThrows(TeamsException.class, () -> dir.resolveTeamId("Dup"));
    }

    @Test
    @DisplayName("resolveChannelId matches by name; a 19: id passes through without HTTP")
    void resolveChannelId() {
        server.respond(r -> StubServer.Response.json(
                "{\"value\":[{\"id\":\"19:abc@thread.tacv2\",\"displayName\":\"General\"}]}"));
        assertEquals("19:abc@thread.tacv2", dir.resolveChannelId("T1", "general"));

        var before = server.requestCount();
        assertEquals("19:raw@thread.tacv2", dir.resolveChannelId("T1", "19:raw@thread.tacv2"));
        assertEquals(before, server.requestCount(), "id passthrough makes no HTTP call");
    }
}
