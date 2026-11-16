package com.macmario.services.comm.teams.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.auth.TokenSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * File-backed {@link TokenStore} for the CLI. Persists the token set as JSON in an
 * owner-only file (POSIX {@code rw-------} where supported).
 *
 * <p>Default location: {@code $TEAMS_TOKEN_STORE}, else
 * {@code ~/.config-app/token.teams.json}. The file holds a long-lived refresh token —
 * treat it like a credential.
 */
public final class FileTokenStore implements TokenStore {

    private final Path file;
    private final ObjectMapper mapper;

    public FileTokenStore(Path file) {
        this(file, new ObjectMapper());
    }

    public FileTokenStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /** Resolves the default store location from the environment or the user's home. */
    public static FileTokenStore defaultStore() {
        var override = System.getenv("TEAMS_TOKEN_STORE");
        var path = (override != null && !override.isBlank())
                ? Path.of(override)
                : Path.of(System.getProperty("user.home", "."), ".config-app", "token.teams.json");
        return new FileTokenStore(path);
    }

    @Override
    public Optional<TokenSet> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            var accessToken = text(node, "accessToken");
            var expiresAt = text(node, "expiresAt");
            if (accessToken == null || expiresAt == null) {
                return Optional.empty();
            }
            return Optional.of(new TokenSet(accessToken, text(node, "refreshToken"), Instant.parse(expiresAt)));
        } catch (IOException | DateTimeParseException e) {
            // Corrupt/unreadable store is treated as "not signed in".
            return Optional.empty();
        }
    }

    @Override
    public void save(TokenSet tokenSet) {
        Path tmp = null;
        try {
            ensureParentDir();
            ObjectNode node = mapper.createObjectNode();
            node.put("accessToken", tokenSet.accessToken());
            if (tokenSet.refreshToken() != null) {
                node.put("refreshToken", tokenSet.refreshToken());
            }
            node.put("expiresAt", tokenSet.expiresAt().toString());

            // Write to a temp file in the same directory (created rw------- on POSIX),
            // then atomically move it into place so the token is never world-readable
            // or left half-written.
            var dir = file.getParent() != null ? file.getParent() : Path.of(".");
            tmp = Files.createTempFile(dir, ".token", ".tmp");
            restrictToOwner(tmp);
            Files.writeString(tmp, mapper.writeValueAsString(node), StandardCharsets.UTF_8);
            restrictToOwner(tmp);
            moveInto(tmp, file);
            restrictToOwner(file);
            tmp = null;
        } catch (IOException e) {
            throw new TeamsException("Failed to write token store: " + file, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // leftover temp file is harmless
                }
            }
        }
    }

    private static void moveInto(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public boolean clear() {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new TeamsException("Failed to remove token store: " + file, e);
        }
    }

    @Override
    public String location() {
        return file.toString();
    }

    private void ensureParentDir() throws IOException {
        var parent = file.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
            restrictDirToOwner(parent);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX (e.g. Windows): best effort only.
        }
    }

    private static void restrictDirToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX: best effort only.
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
