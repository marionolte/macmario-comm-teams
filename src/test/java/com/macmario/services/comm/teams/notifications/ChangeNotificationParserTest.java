package com.macmario.services.comm.teams.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.TeamsException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChangeNotificationParserTest {

    private final ChangeNotificationParser parser = new ChangeNotificationParser();

    @Test
    @DisplayName("validationToken is URL-decoded from the query string")
    void validationToken_decoded() {
        assertEquals("abc 123", parser.validationToken("validationToken=abc%20123").orElseThrow());
    }

    @Test
    @DisplayName("validationToken is empty for non-validation requests")
    void validationToken_absent() {
        assertTrue(parser.validationToken(null).isEmpty());
        assertTrue(parser.validationToken("").isEmpty());
        assertTrue(parser.validationToken("foo=bar").isEmpty());
    }

    @Test
    @DisplayName("parse extracts fields including resourceData.id")
    void parse_extractsFields() {
        var body = """
            {"value":[
              {"subscriptionId":"sub-1","changeType":"created","clientState":"s3cr3t",
               "tenantId":"ten-1","resource":"teams('t')/channels('c')/messages('m')",
               "resourceData":{"id":"m-99"}}
            ]}""";

        List<ChangeNotification> notifications = parser.parse(body);

        assertEquals(1, notifications.size());
        var n = notifications.get(0);
        assertEquals("sub-1", n.subscriptionId());
        assertEquals("created", n.changeType());
        assertEquals("m-99", n.resourceId());
        assertEquals("ten-1", n.tenantId());
    }

    @Test
    @DisplayName("clientState comparison validates and rejects correctly")
    void clientState_validation() {
        var body = "{\"value\":[{\"subscriptionId\":\"s\",\"clientState\":\"expected\"}]}";
        var n = parser.parse(body).get(0);
        assertTrue(n.hasValidClientState("expected"));
        assertFalse(n.hasValidClientState("wrong"));
        assertFalse(n.hasValidClientState(null));
    }

    @Test
    @DisplayName("empty or missing value array yields an empty list")
    void parse_empty() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("{}").isEmpty());
        assertTrue(parser.parse("{\"value\":[]}").isEmpty());
    }

    @Test
    @DisplayName("invalid JSON raises TeamsException")
    void parse_invalidJson_throws() {
        assertThrows(TeamsException.class, () -> parser.parse("not json"));
    }
}
