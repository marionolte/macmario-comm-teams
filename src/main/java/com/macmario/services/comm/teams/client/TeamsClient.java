package com.macmario.services.comm.teams.client;

import com.macmario.services.comm.teams.model.CurrentUser;
import com.macmario.services.comm.teams.model.OutgoingMessage;
import com.macmario.services.comm.teams.model.TeamsMessage;
import java.util.List;

/**
 * Sends and receives Microsoft Teams messages via Microsoft Graph.
 *
 * <p>This is the primary API a host web application depends on. Obtain an instance
 * from {@link TeamsClients}. Implementations are thread-safe and hold no per-call
 * state, so a single instance can be shared across requests.
 *
 * <p><strong>Permissions.</strong> Sending (the {@code send*} methods) requires a
 * <em>delegated</em> token — supply the signed-in user's token through the
 * configured token provider. Reading (the {@code fetch*} methods) works with
 * either a delegated token or an admin-consented application token.
 */
public interface TeamsClient {

    /**
     * Posts a message to a channel.
     *
     * @return the id of the created message
     */
    String sendChannelMessage(String teamId, String channelId, OutgoingMessage message);

    /**
     * Posts a message to an existing chat (1:1 or group).
     *
     * @return the id of the created message
     */
    String sendChatMessage(String chatId, OutgoingMessage message);

    /**
     * Sends a direct message to a single user, creating (or reusing) a 1:1 chat
     * between the signed-in user and the target user first. Requires a delegated
     * token (uses {@code /me} to resolve the sender).
     *
     * @param userId the target user's id or userPrincipalName
     * @return the id of the created message
     */
    String sendDirectMessage(String userId, OutgoingMessage message);

    /**
     * Ensures a 1:1 chat exists between the signed-in user and {@code userId}.
     *
     * @return the chat id
     */
    String ensureOneOnOneChat(String userId);

    /**
     * Fetches the most recent messages in a channel, newest first.
     *
     * @param top maximum number of messages to return (Graph caps channel reads at 50)
     */
    List<TeamsMessage> fetchChannelMessages(String teamId, String channelId, int top);

    /**
     * Fetches the most recent messages in a chat, newest first.
     *
     * @param top maximum number of messages to return
     */
    List<TeamsMessage> fetchChatMessages(String chatId, int top);

    /**
     * Fetches a single message by id from a channel. Useful after a change
     * notification, which carries only the message id, not its content.
     */
    TeamsMessage getChannelMessage(String teamId, String channelId, String messageId);

    /**
     * Fetches a single message by id from a chat. Useful after a change
     * notification, which carries only the message id, not its content.
     */
    TeamsMessage getChatMessage(String chatId, String messageId);

    /**
     * Returns the signed-in user ({@code GET /me}). Requires a delegated token;
     * useful immediately after a login to identify who signed in.
     */
    CurrentUser fetchCurrentUser();
}
