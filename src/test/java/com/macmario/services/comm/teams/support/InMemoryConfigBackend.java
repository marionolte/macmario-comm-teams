package com.macmario.services.comm.teams.support;

import com.macmario.services.comm.teams.config.ConfigBackend;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** In-memory {@link ConfigBackend} for tests (no database). */
public final class InMemoryConfigBackend implements ConfigBackend {

    private final Map<String, String> map = new LinkedHashMap<>();
    private boolean appExists;

    @Override
    public boolean appExists() {
        return appExists;
    }

    @Override
    public Properties getAll() {
        var props = new Properties();
        map.forEach(props::setProperty);
        return props;
    }

    @Override
    public String get(String key) {
        return map.get(key);
    }

    @Override
    public void upsert(String key, String value, boolean secret) {
        appExists = true;
        map.put(key, value);
    }

    /** Marks the app as existing without storing anything (for load tests). */
    public void markAppExists() {
        appExists = true;
    }
}
