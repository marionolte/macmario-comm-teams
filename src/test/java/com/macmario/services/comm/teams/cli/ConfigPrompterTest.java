package com.macmario.services.comm.teams.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.macmario.services.comm.teams.support.InMemoryConfigBackend;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigPrompterTest {

    private static ConfigPrompter prompter(InMemoryConfigBackend backend, String input) {
        var in = new BufferedReader(new StringReader(input));
        var out = new PrintWriter(new StringWriter());
        return new ConfigPrompter(backend, in, out, null); // no console
    }

    @Test
    @DisplayName("stores entered values; blank optional fields are not stored")
    void storesEntered() {
        var backend = new InMemoryConfigBackend();
        // Order: tenantId, clientId, clientSecret, username, password, authority, scope, webhookUrl, graphBaseUrl
        var input = String.join("\n",
                "tid", "cid", "", "user@contoso.com", "pw", "", "", "", "") + "\n";

        prompter(backend, input).run();

        assertEquals("tid", backend.get("teams.tenantId"));
        assertEquals("cid", backend.get("teams.clientId"));
        assertEquals("user@contoso.com", backend.get("teams.username"));
        assertEquals("pw", backend.get("teams.password"));
        assertNull(backend.get("teams.clientSecret")); // optional + blank → not stored
        assertNull(backend.get("teams.webhookUrl"));
    }

    @Test
    @DisplayName("input ending early (EOF) throws instead of looping forever")
    void eof_throws() {
        var backend = new InMemoryConfigBackend();
        // Only two lines, then EOF before the required fields are all provided.
        org.junit.jupiter.api.Assertions.assertThrows(
                com.macmario.services.comm.teams.TeamsException.class,
                () -> prompter(backend, "tid\ncid").run());
    }

    @Test
    @DisplayName("blank input keeps existing values")
    void blankKeepsExisting() {
        var backend = new InMemoryConfigBackend();
        backend.upsert("teams.tenantId", "old-tid", false);
        backend.upsert("teams.clientId", "old-cid", false);
        backend.upsert("teams.username", "old-user", false);
        backend.upsert("teams.password", "old-pw", true);

        var allBlank = "\n\n\n\n\n\n\n\n\n";
        prompter(backend, allBlank).run();

        assertEquals("old-tid", backend.get("teams.tenantId"));
        assertEquals("old-pw", backend.get("teams.password"));
    }
}
