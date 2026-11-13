package com.macmario.services.comm.teams.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal Adaptive Card for incoming-webhook notifications.
 *
 * <p>{@link #toEnvelope()} produces the modern Adaptive Card message envelope
 * ({@code type: message} with an {@code application/vnd.microsoft.card.adaptive}
 * attachment), which is accepted by both legacy Office 365 connector webhooks and
 * the newer Teams Workflows webhooks. The envelope is returned as plain JDK
 * collections so that no JSON-library type appears in the public API; the webhook
 * sender serializes it.
 */
public final class AdaptiveCard {

    private static final String SCHEMA = "http://adaptivecards.io/schemas/adaptive-card.json";

    private final String title;
    private final String text;
    private final String version;
    private final List<Map<String, String>> facts;

    private AdaptiveCard(Builder builder) {
        this.title = builder.title;
        this.text = builder.text;
        this.version = builder.version;
        this.facts = List.copyOf(builder.facts);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience for a plain title/text card. */
    public static AdaptiveCard of(String title, String text) {
        return builder().title(title).text(text).build();
    }

    /**
     * @return the Teams webhook message envelope as nested {@link Map}/{@link List}
     *         structures ready to be serialized to JSON.
     */
    public Map<String, Object> toEnvelope() {
        List<Map<String, Object>> body = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            body.add(textBlock(title, true));
        }
        if (text != null && !text.isBlank()) {
            body.add(textBlock(text, false));
        }
        if (!facts.isEmpty()) {
            body.add(factSet());
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", SCHEMA);
        card.put("type", "AdaptiveCard");
        card.put("version", version);
        card.put("body", body);

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("contentUrl", null);
        attachment.put("content", card);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "message");
        envelope.put("attachments", List.of(attachment));
        return envelope;
    }

    private static Map<String, Object> textBlock(String value, boolean heading) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "TextBlock");
        block.put("text", value);
        block.put("wrap", true);
        if (heading) {
            block.put("weight", "Bolder");
            block.put("size", "Large");
        }
        return block;
    }

    private Map<String, Object> factSet() {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("type", "FactSet");
        set.put("facts", facts);
        return set;
    }

    public static final class Builder {
        private String title;
        private String text;
        private String version = "1.4";
        private final List<Map<String, String>> facts = new ArrayList<>();

        public Builder title(String title) { this.title = title; return this; }
        public Builder text(String text) { this.text = text; return this; }
        public Builder version(String version) { this.version = version; return this; }

        public Builder addFact(String name, String value) {
            Map<String, String> fact = new LinkedHashMap<>();
            fact.put("title", name);
            fact.put("value", value);
            facts.add(fact);
            return this;
        }

        public AdaptiveCard build() {
            return new AdaptiveCard(this);
        }
    }
}
