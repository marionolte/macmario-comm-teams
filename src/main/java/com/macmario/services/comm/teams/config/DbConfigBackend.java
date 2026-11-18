package com.macmario.services.comm.teams.config;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.db.DBConfig;
import java.util.Objects;
import java.util.Properties;

/**
 * {@link ConfigBackend} backed by the {@code macmario-services-db} configdb
 * ({@link DBConfig}, H2-backed; the DB lives under {@code ${user.dir}/.config-app/}).
 *
 * <p>Uses the component's own API — the same methods {@code DbMain} uses — so behaviour
 * matches the {@code -showconfig} interactive tool:
 * <ul>
 *   <li>read: {@link DBConfig#getAllKeyProperty(String, String)} with an empty key prefix
 *       returns every {@code config_key → config_value} for the app;</li>
 *   <li>create: {@link DBConfig#addProperty} (INSERT);</li>
 *   <li>update: {@code DBConfig.getInstance(app).setValue(app, key, value)} (UPDATE).</li>
 * </ul>
 *
 * <p><strong>No encryption here.</strong> These component methods store and return values
 * as-is; any encryption is handled outside this project. (Do <em>not</em> switch reads to
 * {@code DBConfig.getProperty}: its guard treats the freshly-created empty {@code Properties}
 * as "empty" and returns before running the query, so it always yields nothing.)
 *
 * <p>Adapter written against the component's API as used by {@code DbMain}; if the live
 * component differs, adjust this one file. {@code Main} calls {@link DBConfig#init()} once.
 */
public final class DbConfigBackend implements ConfigBackend {

    /** The app namespace for this project's configuration. */
    public static final String APP = "comm.teams";

    private final String app;

    public DbConfigBackend() {
        this(APP);
    }

    public DbConfigBackend(String app) {
        this.app = app;
    }

    @Override
    public boolean appExists() {
        // "Configured" = at least one config entry exists for this app.
        return !readAll().isEmpty();
    }

    @Override
    public Properties getAll() {
        return readAll();
    }

    @Override
    public String get(String key) {
        return readAll().getProperty(key);
    }

    @Override
    public void upsert(String key, String value, boolean secret) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        try {
            if (!DBConfig.isAppExist(app)) {
                DBConfig.addApp(app);
            }
            if (readAll().containsKey(key)) {
                DBConfig.getInstance(app).setValue(app, key, value); // UPDATE existing
            } else {
                DBConfig.addProperty(app, key, value, "comm.teams configuration", "Teams",
                        secret ? "PASSWORD" : "STRING", "false"); // INSERT new
            }
        } catch (RuntimeException e) {
            throw new TeamsException("Failed to write config key '" + key + "' to configdb ("
                    + location() + ")", e);
        }
        // Verify the write took effect (the component's write methods don't report failure
        // reliably, and their concat SQL rejects values containing a single quote).
        if (!value.equals(get(key))) {
            throw new TeamsException("Config key '" + key + "' did not persist to configdb ("
                    + location() + ")");
        }
    }

    /** The on-disk location of the configdb (H2 under the working directory). */
    public static String location() {
        return System.getProperty("user.dir", ".") + "/.config-app";
    }

    private Properties readAll() {
        try {
            var props = DBConfig.getAllKeyProperty(app, "");
            return props != null ? props : new Properties();
        } catch (RuntimeException e) {
            throw new TeamsException("Failed to read configdb (" + location() + ")", e);
        }
    }
}
