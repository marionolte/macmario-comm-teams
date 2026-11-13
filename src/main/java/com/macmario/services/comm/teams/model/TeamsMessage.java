package com.macmario.services.comm.teams.model;

import java.time.Instant;

/**
 * A message received from a Teams channel or chat.
 *
 * <p>A flat, dependency-free projection of a Graph {@code chatMessage}: no Jackson
 * or Graph SDK types leak into the public API. {@code createdDateTime} is
 * {@code null} when Graph did not supply a parseable timestamp.
 *
 * @param id              the message id
 * @param content         the message body text (as returned, may contain HTML)
 * @param contentType     {@code "text"} or {@code "html"}
 * @param fromDisplayName display name of the sender, or {@code null} (e.g. system messages)
 * @param fromId          the sender's user id, or {@code null}
 * @param createdDateTime when the message was created, or {@code null} if unavailable
 */
public record TeamsMessage(
        String id,
        String content,
        String contentType,
        String fromDisplayName,
        String fromId,
        Instant createdDateTime) {
}
