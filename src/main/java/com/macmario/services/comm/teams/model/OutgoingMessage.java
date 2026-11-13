package com.macmario.services.comm.teams.model;

import java.util.Objects;

/**
 * A message to post to a Teams channel or chat via Microsoft Graph.
 *
 * @param content     the message body (plain text, or HTML when {@code html} is true)
 * @param contentType the body content type
 */
public record OutgoingMessage(String content, ContentType contentType) {

    public enum ContentType {
        TEXT("text"),
        HTML("html");

        private final String graphValue;

        ContentType(String graphValue) {
            this.graphValue = graphValue;
        }

        /** The value Microsoft Graph expects in {@code body.contentType}. */
        public String graphValue() {
            return graphValue;
        }
    }

    public OutgoingMessage {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentType, "contentType");
        if (content.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank");
        }
    }

    /** A plain-text message. */
    public static OutgoingMessage text(String content) {
        return new OutgoingMessage(content, ContentType.TEXT);
    }

    /** An HTML message (supports mentions, formatting, links). */
    public static OutgoingMessage html(String content) {
        return new OutgoingMessage(content, ContentType.HTML);
    }
}
