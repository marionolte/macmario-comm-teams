package com.macmario.services.comm.teams.auth;

import com.macmario.services.comm.teams.TeamsException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Serves a bearer token supplied by the caller.
 *
 * <p>This is the primary send path for a host web application: the application
 * performs its own OAuth sign-in and hands the signed-in user's delegated token
 * to the library. Prefer the {@link Supplier} constructor so the application can
 * return a freshly refreshed token on each call; the plain-{@code String}
 * constructor is a convenience for short-lived or test usage.
 */
public final class StaticTokenProvider implements AccessTokenProvider {

    private final Supplier<String> source;

    public StaticTokenProvider(String token) {
        var fixed = Objects.requireNonNull(token, "token");
        this.source = () -> fixed;
    }

    public StaticTokenProvider(Supplier<String> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public String token() {
        var value = source.get();
        if (value == null || value.isBlank()) {
            throw new TeamsException("Token supplier returned no token");
        }
        return value;
    }
}
