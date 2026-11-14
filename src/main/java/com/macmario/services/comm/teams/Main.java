package com.macmario.services.comm.teams;

import com.macmario.services.comm.teams.auth.DeviceCodeTokenProvider;
import com.macmario.services.comm.teams.auth.TokenSet;
import com.macmario.services.comm.teams.cli.FileTokenStore;
import com.macmario.services.comm.teams.cli.TokenStore;
import com.macmario.services.comm.teams.client.TeamsClient;
import com.macmario.services.comm.teams.client.TeamsClients;
import com.macmario.services.comm.teams.config.TeamsConfig;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.model.OutgoingMessage;
import com.macmario.services.comm.teams.model.TeamsMessage;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line driver for the comm.teams library.
 *
 * <p>This is a thin wrapper: all behavior lives in the library types, which a web
 * application uses directly. Configuration is read from environment variables (see
 * {@link TeamsConfig#fromEnv()}). Graph operations sign in interactively via the
 * device-code flow, printing a code to enter in a browser.
 *
 * <pre>
 *   user-login
 *   user-logout
 *   webhook        &lt;text...&gt;
 *   send-channel   &lt;teamId&gt; &lt;channelId&gt; &lt;text...&gt;
 *   send-chat      &lt;chatId&gt; &lt;text...&gt;
 *   send-user      &lt;userId&gt; &lt;text...&gt;
 *   fetch-channel  &lt;teamId&gt; &lt;channelId&gt; [top]
 *   fetch-chat     &lt;chatId&gt; [top]
 * </pre>
 */
public final class Main {

    private Main() {
    }

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
        var config = TeamsConfig.fromEnv();
        var store = FileTokenStore.defaultStore();
        var command = args[0];
        switch (command) {
            case "user-login" -> userLogin(config, store);
            case "user-logout" -> userLogout(store);
            case "webhook" -> {
                var text = joinFrom(args, 1, "webhook <text...>");
                TeamsClients.webhook(config).send(text);
                System.out.println("Notification sent.");
            }
            case "send-channel" -> {
                requireArgs(args, 4, "send-channel <teamId> <channelId> <text...>");
                var id = client(config, store).sendChannelMessage(args[1], args[2], OutgoingMessage.text(joinFrom(args, 3, "")));
                System.out.println("Sent message id=" + id);
            }
            case "send-chat" -> {
                requireArgs(args, 3, "send-chat <chatId> <text...>");
                var id = client(config, store).sendChatMessage(args[1], OutgoingMessage.text(joinFrom(args, 2, "")));
                System.out.println("Sent message id=" + id);
            }
            case "send-user" -> {
                requireArgs(args, 3, "send-user <userId> <text...>");
                var id = client(config, store).sendDirectMessage(args[1], OutgoingMessage.text(joinFrom(args, 2, "")));
                System.out.println("Sent message id=" + id);
            }
            case "fetch-channel" -> {
                requireArgs(args, 3, "fetch-channel <teamId> <channelId> [top]");
                print(client(config, store).fetchChannelMessages(args[1], args[2], optionalTop(args, 3)));
            }
            case "fetch-chat" -> {
                requireArgs(args, 2, "fetch-chat <chatId> [top]");
                print(client(config, store).fetchChatMessages(args[1], optionalTop(args, 2)));
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    /** Interactive device-code sign-in; persists the token for later commands. */
    private static void userLogin(TeamsConfig config, TokenStore store) {
        var transport = HttpTransport.createDefault();
        var device = new DeviceCodeTokenProvider(transport, config);
        TokenSet tokens = device.login();
        store.save(tokens);
        try {
            var me = TeamsClients.graphWith(transport, config, device).fetchCurrentUser();
            var name = me.displayName() != null ? me.displayName() : me.userPrincipalName();
            System.out.println("Signed in as " + name + ". Token saved to " + store.location());
        } catch (TeamsException e) {
            System.out.println("Signed in; token saved to " + store.location()
                    + " (could not read profile: " + e.getMessage() + ")");
        }
    }

    /** Clears the locally stored token (local sign-out). */
    private static void userLogout(TokenStore store) {
        boolean removed = store.clear();
        System.out.println(removed
                ? "Signed out; stored token removed."
                : "No stored token to remove.");
    }

    /**
     * Builds a Graph client, preferring the persisted token from {@code user-login};
     * if none is stored, falls back to an interactive device-code sign-in for this
     * command only (not persisted).
     */
    private static TeamsClient client(TeamsConfig config, TokenStore store) {
        var transport = HttpTransport.createDefault();
        return store.load()
                .map(tokens -> TeamsClients.graphAfterDeviceLogin(transport, config, tokens, store::save))
                .orElseGet(() -> TeamsClients.graphWith(transport, config,
                        new DeviceCodeTokenProvider(transport, config)));
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
            Configure via environment variables (TEAMS_TENANT_ID, TEAMS_CLIENT_ID,
            TEAMS_WEBHOOK_URL, ...). See CLAUDE.md.

            Commands:
              user-login                                  interactive sign-in (device code), token saved
              user-logout                                 clear the stored token (local sign-out)
              webhook        <text...>
              send-channel   <teamId> <channelId> <text...>
              send-chat      <chatId> <text...>
              send-user      <userId> <text...>
              fetch-channel  <teamId> <channelId> [top]
              fetch-chat     <chatId> [top]

            Graph commands use the token from 'user-login' if present, otherwise
            prompt for a one-off device-code sign-in.""";
    }
}
