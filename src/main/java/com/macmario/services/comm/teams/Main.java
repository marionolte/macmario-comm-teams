package com.macmario.services.comm.teams;

import com.macmario.services.comm.teams.auth.AccessTokenProvider;
import com.macmario.services.comm.teams.auth.GraphScopes;
import com.macmario.services.comm.teams.auth.PasswordTokenProvider;
import com.macmario.services.comm.teams.auth.RefreshingTokenProvider;
import com.macmario.services.comm.teams.auth.TokenSet;
import com.macmario.services.comm.teams.cli.ConfigPrompter;
import com.macmario.services.comm.teams.cli.FileTokenStore;
import com.macmario.services.comm.teams.cli.TokenStore;
import com.macmario.services.comm.teams.client.TeamsClient;
import com.macmario.services.comm.teams.client.TeamsClients;
import com.macmario.services.comm.teams.client.TeamsDirectory;
import com.macmario.services.comm.teams.config.DbConfigBackend;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.model.CurrentUser;
import com.macmario.services.comm.teams.model.OutgoingMessage;
import com.macmario.services.comm.teams.model.TeamsMessage;
import com.macmario.services.db.AppVersion;
import com.macmario.services.db.DBConfig;
import com.macmario.services.db.DbMain;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line driver for the comm.teams library.
 *
 * <p>Configuration lives in the configdb (app {@code comm.teams}); run {@code -config}
 * to set it up. The CLI signs in with username/password (ROPC) using the stored
 * credentials, caches the token, and resolves team/channel names to IDs on demand.
 *
 * <pre>
 *   -config
 *   -showconfig
 *   user-login
 *   user-logout
 *   webhook        &lt;text...&gt;
 *   send-channel   &lt;team|id&gt; &lt;channel|id&gt; &lt;text...&gt;
 *   send-chat      &lt;chat|id&gt; &lt;text...&gt;
 *   send-user      &lt;userId|upn&gt; &lt;text...&gt;
 *   fetch-channel  &lt;team|id&gt; &lt;channel|id&gt; [top]
 *   fetch-chat     &lt;chat|id&gt; [top]
 *   list-teams
 *   list-channels  &lt;team|id&gt;
 *   list-chats
 * </pre>
 */
public final class Main extends AppVersion {
    
    private Main() {
    }

    /** Bundles a Graph client and directory sharing one transport + token provider. */
    private record Graph(TeamsClient client, TeamsDirectory dir) {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println(usage());
            System.exit(2);
            return;
        }
        try {
            run(args);
        } catch (TeamsException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(usage());
            System.exit(2);
        }
    }

    private static void run(String[] args) {
        var command = args[0];
        if ("-config".equals(command)) {
            configure();
            return;
        }
        if ("-showconfig".equals(command)) {
            showConfig(args);
            return;
        }

        var config = loadConfig();
        var store = FileTokenStore.defaultStore();
        switch (command) {
            case "user-login" -> userLogin(config, store);
            case "user-logout" -> userLogout(store);
            case "webhook" -> {
                var text = joinFrom(args, 1, "webhook <text...>");
                TeamsClients.webhook(config).send(text);
                System.out.println("Notification sent.");
            }
            case "send-channel" -> {
                requireArgs(args, 4, "send-channel <team|id> <channel|id> <text...>");
                var g = graph(config, store);
                var teamId = g.dir().resolveTeamId(args[1]);
                var channelId = g.dir().resolveChannelId(teamId, args[2]);
                var id = g.client().sendChannelMessage(teamId, channelId,
                        OutgoingMessage.text(joinFrom(args, 3, "")));
                System.out.println("Sent message id=" + id);
            }
            case "send-chat" -> {
                requireArgs(args, 3, "send-chat <chat|id> <text...>");
                var g = graph(config, store);
                var chatId = g.dir().resolveChatId(args[1]);
                var id = g.client().sendChatMessage(chatId, OutgoingMessage.text(joinFrom(args, 2, "")));
                System.out.println("Sent message id=" + id);
            }
            case "send-user" -> {
                requireArgs(args, 3, "send-user <userId|upn> <text...>");
                var id = graph(config, store).client()
                        .sendDirectMessage(args[1], OutgoingMessage.text(joinFrom(args, 2, "")));
                System.out.println("Sent message id=" + id);
            }
            case "fetch-channel" -> {
                requireArgs(args, 3, "fetch-channel <team|id> <channel|id> [top]");
                var g = graph(config, store);
                var teamId = g.dir().resolveTeamId(args[1]);
                var channelId = g.dir().resolveChannelId(teamId, args[2]);
                print(g.client().fetchChannelMessages(teamId, channelId, optionalTop(args, 3)));
            }
            case "fetch-chat" -> {
                requireArgs(args, 2, "fetch-chat <chat|id> [top]");
                var g = graph(config, store);
                var chatId = g.dir().resolveChatId(args[1]);
                print(g.client().fetchChatMessages(chatId, optionalTop(args, 2)));
            }
            case "list-teams" -> {
                for (var t : graph(config, store).dir().listJoinedTeams()) {
                    System.out.println(t.displayName() + "  [" + t.id() + "]");
                }
            }
            case "list-channels" -> {
                requireArgs(args, 2, "list-channels <team|id>");
                var g = graph(config, store);
                var teamId = g.dir().resolveTeamId(args[1]);
                for (var c : g.dir().listChannels(teamId)) {
                    System.out.println(c.displayName() + "  [" + c.id() + "]");
                }
            }
            case "list-chats" -> {
                for (var c : graph(config, store).dir().listChats()) {
                    var label = c.topic() != null ? c.topic() : "(" + c.chatType() + ")";
                    System.out.println(label + "  [" + c.id() + "]");
                }
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    // --- configuration --------------------------------------------------------
    
    /** Interactive `-showconfig`: show th configured settings of comm.teams in the the configdb. */
    private static void showConfig(String[] args) {
        if ( isNotNullOrEmpty(args) ) {
            String[] sp = new String[args.length-1];
            for (int i=0; i<sp.length;i++) { sp[i]=args[i+1]; }
            args=sp;
        }
        DbMain.main(args);
    }

    /** Interactive `-config`: prompts for the comm.teams settings and writes the configdb. */
    private static void configure() {
        DBConfig.init(); // create/open the configdb once (CREATE TABLE IF NOT EXISTS)
        var backend = new DbConfigBackend();
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        new ConfigPrompter(backend, in, out, System.console()).run();
        // Credentials may have changed: drop any cached token so the next command
        // signs in as the newly configured user.
        FileTokenStore.defaultStore().clear();
        System.out.println("configdb: " + DbConfigBackend.location()
                + " — run 'user-login' from this directory.");
    }

    /** Config from the configdb when configured, else from environment variables. */
    private static TeamsConfig loadConfig() {
        DBConfig.init(); // open the configdb (idempotent) so the JDBC reads below work
        var backend = new DbConfigBackend();
        // Prefer the configdb only when it actually has settings — the app namespace can
        // exist but be empty, in which case fall back to environment variables.
        var config = backend.appExists() ? TeamsConfig.fromConfigBackend(backend) : TeamsConfig.fromEnv();
        if (config.tenantId() == null && config.webhookUrl() == null) {
            System.err.println("No comm.teams configuration found (configdb: " + DbConfigBackend.location()
                    + ", and no TEAMS_* environment variables set). "
                    + "Run '-config' from this directory first, then 'user-login'.");
        }
        return config;
    }

    // --- login ----------------------------------------------------------------

    /** ROPC sign-in with the configured username/password; persists the token and shows IDs. */
    private static void userLogin(TeamsConfig config, TokenStore store) {
        var transport = HttpTransport.createDefault();
        var ropc = new PasswordTokenProvider(transport, config);
        TokenSet tokens = ropc.login();
        store.save(tokens);

        try {
            CurrentUser me = TeamsClients.graphWith(transport, config, ropc).fetchCurrentUser();
            System.out.println("Signed in as " + displayName(me) + ". Token saved to " + store.location());
        } catch (TeamsException e) {
            System.out.println("Signed in; token saved to " + store.location()
                    + " (could not read profile: " + e.getMessage() + ")");
        }
        try {
            var teams = TeamsClients.directory(transport, config, ropc).listJoinedTeams();
            System.out.println("Joined teams (" + teams.size() + "):");
            for (var t : teams) {
                System.out.println("  " + t.displayName() + "  [" + t.id() + "]");
            }
            System.out.println("Use 'list-channels <team>' to see channel IDs.");
        } catch (TeamsException e) {
            System.out.println("(could not list teams: " + e.getMessage() + ")");
        }
    }

    private static void userLogout(TokenStore store) {
        boolean removed = store.clear();
        System.out.println(removed
                ? "Signed out; stored token removed."
                : "No stored token to remove.");
    }

    // --- graph context --------------------------------------------------------

    private static Graph graph(TeamsConfig config, TokenStore store) {
        var transport = HttpTransport.createDefault();
        var provider = tokenProvider(config, store, transport);
        return new Graph(
                TeamsClients.graphWith(transport, config, provider),
                TeamsClients.directory(transport, config, provider));
    }

    /**
     * Uses the cached token (refreshing it; secret sent iff configured) when present,
     * otherwise signs in via ROPC and persists the acquired token.
     */
    private static AccessTokenProvider tokenProvider(TeamsConfig config, TokenStore store,
            HttpTransport transport) {
        var stored = store.load();
        if (stored.isPresent()) {
            return new RefreshingTokenProvider(
                    transport, config.tokenEndpoint(), config.clientId(),
                    config.clientSecret(), GraphScopes.resolve(config.scope()),
                    stored.get(), Clock.systemUTC(), store::save);
        }
        var ropc = new PasswordTokenProvider(transport, config);
        store.save(ropc.login());
        return ropc;
    }

    // --- helpers --------------------------------------------------------------

    private static String displayName(CurrentUser me) {
        return me.displayName() != null ? me.displayName() : me.userPrincipalName();
    }

    private static void print(List<TeamsMessage> messages) {
        if (messages.isEmpty()) {
            System.out.println("(no messages)");
            return;
        }
        for (var m : messages) {
            var who = m.fromDisplayName() != null ? m.fromDisplayName() : "(system)";
            System.out.println("[" + m.createdDateTime() + "] " + who + ": " + m.content());
        }
    }

    private static int optionalTop(String[] args, int index) {
        if (args.length <= index) {
            return 20;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("top must be a number, got: " + args[index]);
        }
    }

    private static String joinFrom(String[] args, int from, String usage) {
        if (args.length <= from) {
            throw new IllegalArgumentException("Missing text. Usage: " + usage);
        }
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static void requireArgs(String[] args, int min, String usage) {
        if (args.length < min) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }

    private static String usage() {
        return """
            comm.teams — Microsoft Teams send/receive CLI
            Configure once with '-config' (writes the configdb); login uses the stored
            username/password. Team/channel arguments accept a name or an id.

            Commands:
              -config                                     configure comm.teams in the configdb
              -showconfig                                 show configdb (interactive)
              user-login                                  sign in (username/password), cache token, show IDs
              user-logout                                 clear the cached token
              webhook        <text...>
              send-channel   <team|id> <channel|id> <text...>
              send-chat      <chat|id> <text...>
              send-user      <userId|upn> <text...>
              fetch-channel  <team|id> <channel|id> [top]
              fetch-chat     <chat|id> [top]
              list-teams
              list-channels  <team|id>
              list-chats""";
    }
}
