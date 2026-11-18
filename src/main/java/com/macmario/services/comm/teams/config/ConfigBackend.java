package com.macmario.services.comm.teams.config;

import java.util.Properties;

/**
 * Abstraction over a key/value configuration store for the {@code comm.teams} app.
 *
 * <p>Implemented by {@code DbConfigBackend} (the real configdb) and by an in-memory
 * fake in tests. Password-typed values are stored encrypted by the backend and
 * returned decrypted by {@link #get} / {@link #getAll}.
 */
public interface ConfigBackend {

    /** @return whether the {@code comm.teams} app namespace exists in the store. */
    boolean appExists();

    /** @return all stored properties (full keys, password values decrypted). */
    Properties getAll();

    /** @return the decrypted value for {@code key}, or {@code null} if absent. */
    String get(String key);

    /**
     * Creates or updates {@code key}. When {@code secret} is true the value is marked
     * as a password-typed entry. Note the configdb encrypts a value when the key name
     * itself denotes a password (e.g. contains {@code "password"}).
     */
    void upsert(String key, String value, boolean secret);
}
