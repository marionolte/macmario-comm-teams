package com.macmario.services.comm.teams.auth;

/** Default delegated Graph scopes shared by the interactive sign-in flows. */
public final class GraphScopes {

    /**
     * Covers every library operation: send to channel ({@code ChannelMessage.Send}),
     * send/read chats and create 1:1 ({@code Chat.ReadWrite}), fetch channel messages
     * ({@code ChannelMessage.Read.All}, admin consent), resolve the signed-in user
     * ({@code User.Read}), and discover targets by name — joined teams
     * ({@code Team.ReadBasic.All}) and channels ({@code Channel.ReadBasic.All}).
     * {@code offline_access} is required to obtain a refresh token.
     */
    public static final String DEFAULT_DELEGATED =
            "https://graph.microsoft.com/ChannelMessage.Send "
                    + "https://graph.microsoft.com/Chat.ReadWrite "
                    + "https://graph.microsoft.com/ChannelMessage.Read.All "
                    + "https://graph.microsoft.com/Team.ReadBasic.All "
                    + "https://graph.microsoft.com/Channel.ReadBasic.All "
                    + "https://graph.microsoft.com/User.Read offline_access";

    private GraphScopes() {
    }

    /** Returns {@code configured} if usable for an interactive flow, else the defaults. */
    public static String resolve(String configured) {
        if (configured == null || configured.isBlank() || configured.endsWith("/.default")) {
            return DEFAULT_DELEGATED;
        }
        return configured.contains("offline_access") ? configured : configured + " offline_access";
    }
}
