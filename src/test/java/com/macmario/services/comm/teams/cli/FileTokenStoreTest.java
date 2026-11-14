package com.macmario.services.comm.teams.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macmario.services.comm.teams.auth.TokenSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTokenStoreTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("save then load returns an equal token set, owner-only on POSIX")
    void save_load_roundTrip() throws Exception {
        var file = dir.resolve("sub").resolve("token.json");
        var store = new FileTokenStore(file);
        var tokens = new TokenSet("at", "rt", Instant.parse("2026-01-01T00:00:00Z"));

        store.save(tokens);
        var loaded = store.load().orElseThrow();

        assertEquals("at", loaded.accessToken());
        assertEquals("rt", loaded.refreshToken());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), loaded.expiresAt());

        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var perms = Files.getPosixFilePermissions(file);
            assertEquals("rw-------", PosixFilePermissions.toString(perms));
        }
    }

    @Test
    @DisplayName("load returns empty when no file exists")
    void load_missing_empty() {
        var store = new FileTokenStore(dir.resolve("none.json"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    @DisplayName("load treats a corrupt file as not signed in")
    void load_corrupt_empty() throws Exception {
        var file = dir.resolve("bad.json");
        Files.writeString(file, "not json");
        assertTrue(new FileTokenStore(file).load().isEmpty());
    }

    @Test
    @DisplayName("clear removes the stored token and reports whether one existed")
    void clear_removes() {
        var file = dir.resolve("token.json");
        var store = new FileTokenStore(file);
        assertFalse(store.clear());

        store.save(new TokenSet("at", "rt", Instant.now().plusSeconds(3600)));
        assertTrue(store.clear());
        assertTrue(store.load().isEmpty());
    }
}
