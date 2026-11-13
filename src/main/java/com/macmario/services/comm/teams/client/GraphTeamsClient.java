package com.macmario.services.comm.teams.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.auth.AccessTokenProvider;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.model.CurrentUser;
import com.macmario.services.comm.teams.model.OutgoingMessage;
import com.macmario.services.comm.teams.model.TeamsMessage;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Microsoft Graph implementation of {@link TeamsClient}.
 *
 * <p>Stateless and thread-safe: it holds a shared {@link HttpTransport}, an
 * {@link AccessTokenProvider}, and the Graph base URL. Construct it via
 * {@link TeamsClients}.
 */
public final class GraphTeamsClient implements TeamsClient {

    private final HttpTransport transport;
    private final AccessTokenProvider tokens;
    private final String baseUrl;

    public GraphTeamsClient(HttpTransport transport, AccessTokenProvider tokens, String baseUrl) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    @Override
    public String sendChannelMessage(String teamId, String channelId, OutgoingMessage message) {
        requireId(teamId, "teamId");
        requireId(channelId, "channelId");
        Objects.requireNonNull(message, "message");
        var uri = uri("/teams/" + teamId + "/channels/" + channelId + "/messages");
        var response = transport.postJson(uri, tokens.token(), messageBody(message));
        return requiredText(response, "id");
    }

    @Override
    public String sendChatMessage(String chatId, OutgoingMessage message) {
        requireId(chatId, "chatId");
        Objects.requireNonNull(message, "message");
        var uri = uri("/chats/" + chatId + "/messages");
        var response = transport.postJson(uri, tokens.token(), messageBody(message));
        return requiredText(response, "id");
    }

    @Override
    public String sendDirectMessage(String userId, OutgoingMessage message) {
        var chatId = ensureOneOnOneChat(userId);
        return sendChatMessage(chatId, message);
    }

    @Override
    public String ensureOneOnOneChat(String userId) {
        requireId(userId, "userId");
        var me = requiredText(transport.getJson(uri("/me"), tokens.token()), "id");
        var body = oneOnOneChatBody(me, userId);
        var response = transport.postJson(uri("/chats"), tokens.token(), body);
        return requiredText(response, "id");
    }

    @Override
    public List<TeamsMessage> fetchChannelMessages(String teamId, String channelId, int top) {
        requireId(teamId, "teamId");
        requireId(channelId, "channelId");
        var uri = uri("/teams/" + teamId + "/channels/" + channelId + "/messages?$top=" + topValue(top));
        return parseMessageList(transport.getJson(uri, tokens.token()));
    }

    @Override
    public List<TeamsMessage> fetchChatMessages(String chatId, int top) {
        requireId(chatId, "chatId");
        var uri = uri("/chats/" + chatId + "/messages?$top=" + topValue(top));
        return parseMessageList(transport.getJson(uri, tokens.token()));
    }

    @Override
    public TeamsMessage getChannelMessage(String teamId, String channelId, String messageId) {
        requireId(teamId, "teamId");
        requireId(channelId, "channelId");
        requireId(messageId, "messageId");
        var uri = uri("/teams/" + teamId + "/channels/" + channelId + "/messages/" + messageId);
        return parseMessage(transport.getJson(uri, tokens.token()));
    }

    @Override
    public TeamsMessage getChatMessage(String chatId, String messageId) {
        requireId(chatId, "chatId");
        requireId(messageId, "messageId");
        var uri = uri("/chats/" + chatId + "/messages/" + messageId);
        return parseMessage(transport.getJson(uri, tokens.token()));
    }

    @Override
    public CurrentUser fetchCurrentUser() {
        var node = transport.getJson(uri("/me"), tokens.token());
        return new CurrentUser(
                text(node, "id"),
                text(node, "displayName"),
                text(node, "userPrincipalName"));
    }

    // --- request body builders -------------------------------------------------

    private static Map<String, Object> messageBody(OutgoingMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentType", message.contentType().graphValue());
        body.put("content", message.content());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("body", body);
        return root;
    }

    private Map<String, Object> oneOnOneChatBody(String meId, String otherId) {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("chatType", "oneOnOne");
        chat.put("members", List.of(member(meId), member(otherId)));
        return chat;
    }

    private Map<String, Object> member(String userId) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("@odata.type", "#microsoft.graph.aadUserConversationMember");
        member.put("roles", List.of("owner"));
        member.put("user@odata.bind", baseUrl + "/users('" + userId + "')");
        return member;
    }

    // --- response parsing ------------------------------------------------------

    private List<TeamsMessage> parseMessageList(JsonNode response) {
        var value = response.path("value");
        if (!value.isArray()) {
            return List.of();
        }
        List<TeamsMessage> messages = new ArrayList<>(value.size());
        for (JsonNode node : value) {
            messages.add(parseMessage(node));
        }
        return List.copyOf(messages);
    }

    private static TeamsMessage parseMessage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            throw new TeamsException("Expected a message object but got none");
        }
        var body = node.path("body");
        var user = node.path("from").path("user");
        return new TeamsMessage(
                text(node, "id"),
                text(body, "content"),
                text(body, "contentType"),
                text(user, "displayName"),
                text(user, "id"),
                parseInstant(text(node, "createdDateTime")));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // --- helpers ---------------------------------------------------------------

    private URI uri(String relativePath) {
        return URI.create(baseUrl + relativePath);
    }

    private static int topValue(int top) {
        return top <= 0 ? 20 : top;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null) {
            throw new TeamsException("Graph response missing expected field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
