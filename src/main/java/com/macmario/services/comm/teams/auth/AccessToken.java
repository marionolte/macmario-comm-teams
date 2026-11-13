package com.macmario.services.comm.teams.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * An OAuth2 bearer token with its absolute expiry instant.
 *
 * @param value     the raw bearer token value
 * @param expiresAt the instant at which the token stops being valid
 */
public record AccessToken(String value, Instant expiresAt) {

    /** Builds a token that expires {@code expiresInSeconds} from {@code now}. */
    public static AccessToken expiringIn(String value, long expiresInSeconds, Instant now) {
        return new AccessToken(value, now.plusSeconds(expiresInSeconds));
    }

    /**
     * @return {@code true} when the token is still valid at {@code now} with at
     *         least {@code skew} of headroom before expiry.
     */
    public boolean isValid(Instant now, Duration skew) {
        return now.plus(skew).isBefore(expiresAt);
    }

    /** Redacts the token value so it never leaks into logs or exceptions. */
    @Override
    public String toString() {
        return "AccessToken{value=<redacted>, expiresAt=" + expiresAt + "}";
    }
}
