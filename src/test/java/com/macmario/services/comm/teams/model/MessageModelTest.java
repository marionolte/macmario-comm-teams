package com.macmario.services.comm.teams.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("OutgoingMessage factories set the Graph content type")
    void outgoingMessage_contentType() {
        assertEquals("text", OutgoingMessage.text("hi").contentType().graphValue());
        assertEquals("html", OutgoingMessage.html("<b>hi</b>").contentType().graphValue());
    }

    @Test
    @DisplayName("OutgoingMessage rejects blank content")
    void outgoingMessage_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> OutgoingMessage.text("  "));
    }

    @Test
    @DisplayName("AdaptiveCard builds the Teams message envelope")
    void adaptiveCard_envelope() throws Exception {
        var card = AdaptiveCard.builder()
                .title("Build failed")
                .text("Pipeline #42 failed")
                .addFact("Branch", "main")
                .addFact("Author", "alice")
                .build();

        JsonNode json = mapper.valueToTree(card.toEnvelope());

        assertEquals("message", json.get("type").asText());
        var attachment = json.get("attachments").get(0);
        assertEquals("application/vnd.microsoft.card.adaptive", attachment.get("contentType").asText());

        var body = attachment.get("content").get("body");
        assertEquals("AdaptiveCard", attachment.get("content").get("type").asText());
        assertEquals("Build failed", body.get(0).get("text").asText());
        assertEquals("Bolder", body.get(0).get("weight").asText());
        assertEquals("Pipeline #42 failed", body.get(1).get("text").asText());

        var factSet = body.get(2);
        assertEquals("FactSet", factSet.get("type").asText());
        assertEquals("Branch", factSet.get("facts").get(0).get("title").asText());
        assertEquals("main", factSet.get("facts").get(0).get("value").asText());
    }

    @Test
    @DisplayName("AdaptiveCard omits empty sections")
    void adaptiveCard_textOnly() {
        var card = AdaptiveCard.builder().text("just text").build();
        JsonNode json = mapper.valueToTree(card.toEnvelope());
        var body = json.get("attachments").get(0).get("content").get("body");
        assertEquals(1, body.size());
        assertTrue(body.get(0).get("text").asText().contains("just text"));
    }
}
