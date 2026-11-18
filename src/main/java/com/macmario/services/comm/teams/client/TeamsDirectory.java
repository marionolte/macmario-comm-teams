package com.macmario.services.comm.teams.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.auth.AccessTokenProvider;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.model.Channel;
import com.macmario.services.comm.teams.model.ChatSummary;
import com.macmario.services.comm.teams.model.Team;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Discovers Teams the signed-in user can reach and resolves display names to IDs, so
 * callers can target channels/chats by name instead of raw GUIDs.
 *
 * <p>Stateless and thread-safe. Needs delegated scopes {@code Team.ReadBasic.All} and
 * {@code Channel.ReadBasic.All} (plus {@code Chat.ReadWrite}/{@code Chat.ReadBasic}).
 * Build via {@link TeamsClients#directory}.
 */
public final class TeamsDirectory {

    private static final int CHAT_PAGE_CAP = 10; // follow at most N pages of /me/chats

    private final HttpTransport transport;
    private final AccessTokenProvider tokens;
    private final String baseUrl;

    public TeamsDirectory(HttpTransport transport, AccessTokenProvider tokens, String baseUrl) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    public List<Team> listJoinedTeams() {
        var response = transport.getJson(URI.create(baseUrl + "/me/joinedTeams"), tokens.token());
        List<Team> teams = new ArrayList<>();
        for (JsonNode n : arrayOf(response)) {
            teams.add(new Team(text(n, "id"), text(n, "displayName")));
        }
        return List.copyOf(teams);
    }

    public List<Channel> listChannels(String teamId) {
        requireId(teamId, "teamId");
        var response = transport.getJson(
                URI.create(baseUrl + "/teams/" + teamId + "/channels"), tokens.token());
        List<Channel> channels = new ArrayList<>();
        for (JsonNode n : arrayOf(response)) {
            channels.add(new Channel(text(n, "id"), text(n, "displayName")));
        }
        return List.copyOf(channels);
    }

    public List<ChatSummary> listChats() {
        List<ChatSummary> chats = new ArrayList<>();
        var uri = URI.create(baseUrl + "/me/chats?$top=50");
        for (int page = 0; page < CHAT_PAGE_CAP && uri != null; page++) {
            var response = transport.getJson(uri, tokens.token());
            for (JsonNode n : arrayOf(response)) {
                chats.add(new ChatSummary(text(n, "id"), text(n, "topic"), text(n, "chatType")));
            }
            var next = text(response, "@odata.nextLink");
            uri = next == null ? null : URI.create(next);
        }
        return List.copyOf(chats);
    }

    // --- name/ID resolution ---------------------------------------------------

    /** Returns {@code teamNameOrId} unchanged if it is an id (GUID), else resolves by name. */
    public String resolveTeamId(String teamNameOrId) {
        requireId(teamNameOrId, "team");
        if (isGuid(teamNameOrId)) {
            return teamNameOrId;
        }
        return matchByName("team", teamNameOrId, listJoinedTeams(), Team::displayName, Team::id);
    }

    /** Returns {@code channelNameOrId} unchanged if it is a channel id, else resolves by name. */
    public String resolveChannelId(String teamId, String channelNameOrId) {
        requireId(channelNameOrId, "channel");
        if (isThreadId(channelNameOrId)) {
            return channelNameOrId;
        }
        return matchByName("channel", channelNameOrId, listChannels(teamId),
                Channel::displayName, Channel::id);
    }

    /**
     * Returns {@code chatTopicOrId} unchanged if it is a chat id ({@code 19:}), else
     * resolves a named group chat by its topic. 1:1 chats have no topic — use
     * {@code sendDirectMessage}/{@code send-user} with the user's UPN instead.
     */
    public String resolveChatId(String chatTopicOrId) {
        requireId(chatTopicOrId, "chat");
        if (isThreadId(chatTopicOrId)) {
            return chatTopicOrId;
        }
        return matchByName("chat", chatTopicOrId, listChats(),
                ChatSummary::topic, ChatSummary::id);
    }

    private static <T> String matchByName(String kind, String name, List<T> items,
            java.util.function.Function<T, String> nameOf, java.util.function.Function<T, String> idOf) {
        var wanted = name.strip().toLowerCase(Locale.ROOT);
        List<T> matches = new ArrayList<>();
        for (T item : items) {
            var n = nameOf.apply(item);
            if (n != null && n.strip().toLowerCase(Locale.ROOT).equals(wanted)) {
                matches.add(item);
            }
        }
        if (matches.isEmpty()) {
            throw new TeamsException("No " + kind + " named \"" + name + "\". Available: "
                    + describe(items, nameOf, idOf));
        }
        if (matches.size() > 1) {
            throw new TeamsException("Ambiguous " + kind + " name \"" + name + "\": "
                    + describe(matches, nameOf, idOf) + " — use the id instead");
        }
        return idOf.apply(matches.get(0));
    }

    private static <T> String describe(List<T> items, java.util.function.Function<T, String> nameOf,
            java.util.function.Function<T, String> idOf) {
        if (items.isEmpty()) {
            return "(none)";
        }
        var sb = new StringBuilder();
        for (T item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(nameOf.apply(item)).append("\"=").append(idOf.apply(item));
        }
        return sb.toString();
    }

    /** A 36-char GUID → a team/group id. */
    public static boolean isGuid(String s) {
        return s != null && s.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /** Teams channel/chat ids start with {@code 19:}. */
    public static boolean isThreadId(String s) {
        return s != null && s.startsWith("19:");
    }

    // --- helpers --------------------------------------------------------------

    private static Iterable<JsonNode> arrayOf(JsonNode response) {
        var value = response == null ? null : response.path("value");
        return value != null && value.isArray() ? value : List.of();
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
