package com.macmario.services.comm.teams;

import com.macmario.services.comm.teams.auth.DeviceCodeTokenProvider;
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
        var command = args[0];
        switch (command) {
            case "webhook" -> {
                var text = joinFrom(args, 1, "webhook <text...>");
                TeamsClients.webhook(config).send(text);
                System.out.println("Notification sent.");
            }
            case "send-channel" -> {
                requireArgs(args, 4, "send-channel <teamId> <channelId> <text...>");
                var id = client(config).sendChannelMessage(args[1], args[2], OutgoingMessage.text(joinFrom(args, 3, "")));
                System.out.println("Sent message id=" + id);
            }
            case "send-chat" -> {
                requireArgs(args, 3, "send-chat <chatId> <text...>");
                var id = client(config).sendChatMessage(args[1], OutgoingMessage.text(joinFrom(args, 2, "")));
                System.out.println("Sent message id=" + id);
            }
            case "send-user" -> {
                requireArgs(args, 3, "send-user <userId> <text...>");
                var id = client(config).sendDirectMessage(args[1], OutgoingMessage.text(joinFrom(args, 2, "")));
                System.out.println("Sent message id=" + id);
            }
            case "fetch-channel" -> {
                requireArgs(args, 3, "fetch-channel <teamId> <channelId> [top]");
                print(client(config).fetchChannelMessages(args[1], args[2], optionalTop(args, 3)));
            }
            case "fetch-chat" -> {
                requireArgs(args, 2, "fetch-chat <chatId> [top]");
                print(client(config).fetchChatMessages(args[1], optionalTop(args, 2)));
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private static TeamsClient client(TeamsConfig config) {
        var transport = HttpTransport.createDefault();
        var tokens = new DeviceCodeTokenProvider(transport, config);
        return TeamsClients.graphWith(transport, config, tokens);
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
              webhook        <text...>
              send-channel   <teamId> <channelId> <text...>
              send-chat      <chatId> <text...>
              send-user      <userId> <text...>
              fetch-channel  <teamId> <channelId> [top]
              fetch-chat     <chatId> [top]""";
    }
}
