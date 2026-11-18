package com.macmario.services.comm.teams.cli;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.config.ConfigBackend;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

/**
 * Interactive prompter for {@code -config}: asks for each {@code comm.teams} property
 * and writes it to a {@link ConfigBackend}.
 *
 * <p>Shows the current value as the default (blank input keeps it); secrets are shown
 * only as {@code <set>}. Required fields must end up with a value. A password is read
 * without echo when a {@link Console} is supplied; otherwise it is read from the reader
 * (and the caller is warned it will echo — NetBeans' Run console has no
 * {@code System.console()}).
 *
 * <p>IO is injected so this is testable and console-independent.
 */
public final class ConfigPrompter {

    /** A configurable field: its configdb key, prompt label, and flags. */
    public record Field(String key, String label, boolean secret, boolean required) {}

    /** The fields collected by {@code -config}, in prompt order. */
    public static final List<Field> FIELDS = List.of(
            new Field("teams.tenantId", "Tenant ID (GUID or 'organizations')", false, true),
            new Field("teams.clientId", "Client ID (app registration)", false, true),
            new Field("teams.clientSecret", "Client secret (blank for a public app)", true, false),
            new Field("teams.username", "Teams username (UPN, e.g. user@contoso.com)", false, true),
            new Field("teams.password", "Teams password", true, true),
            new Field("teams.authority", "Authority (blank = login.microsoftonline.com)", false, false),
            new Field("teams.scope", "Scopes (blank = defaults)", false, false),
            new Field("teams.webhookUrl", "Incoming webhook URL (optional)", false, false),
            new Field("teams.graphBaseUrl", "Graph base URL (blank = v1.0)", false, false));

    private final ConfigBackend backend;
    private final BufferedReader in;
    private final PrintWriter out;
    private final Console console; // may be null (no interactive console)

    public ConfigPrompter(ConfigBackend backend, BufferedReader in, PrintWriter out, Console console) {
        this.backend = backend;
        this.in = in;
        this.out = out;
        this.console = console;
    }

    public void run() {
        Properties current = backend.getAll();
        out.println("Configuring comm.teams (press Enter to keep the current value).");
        out.flush();
        for (Field field : FIELDS) {
            var existing = current.getProperty(field.key());
            var entered = ask(field, existing);
            if (entered == null) {
                // End of input (EOF / piped / non-interactive): fail instead of looping.
                throw new TeamsException("Input ended before '" + field.label() + "' was provided");
            }
            if (!entered.isBlank()) {
                // Don't strip secrets — spaces may be significant in a password.
                var value = field.secret() ? entered : entered.strip();
                backend.upsert(field.key(), value, field.secret());
                // upsert verifies the write (read-back); reaching here means it persisted.
                out.println("  saved " + field.key());
                out.flush();
            } else if (field.required() && (existing == null || existing.isBlank())) {
                throw new TeamsException("Required value '" + field.label() + "' was not provided");
            }
            // else: blank input with an existing value → keep it.
        }
        out.println("Configuration saved.");
        out.flush();
    }

    private String ask(Field field, String existing) {
        var shown = field.secret()
                ? (existing != null && !existing.isBlank() ? " [<set>]" : "")
                : (existing != null && !existing.isBlank() ? " [" + existing + "]" : "");
        out.print(field.label() + shown + ": ");
        out.flush();
        if (field.secret() && console != null) {
            char[] pw = console.readPassword();
            return pw == null ? null : new String(pw);
        }
        if (field.secret() && console == null) {
            out.println("(warning: input will be visible)");
            out.flush();
        }
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
