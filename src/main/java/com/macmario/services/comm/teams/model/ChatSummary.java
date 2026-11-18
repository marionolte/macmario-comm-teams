package com.macmario.services.comm.teams.model;

/**
 * A chat the signed-in user participates in (from {@code GET /me/chats}).
 *
 * @param id       the chat id (e.g. {@code 19:...@thread.v2})
 * @param topic    the chat topic, or {@code null} for 1:1 and un-named group chats
 * @param chatType {@code oneOnOne}, {@code group}, or {@code meeting}
 */
public record ChatSummary(String id, String topic, String chatType) {
}
