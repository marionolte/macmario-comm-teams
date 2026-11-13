package com.macmario.services.comm.teams.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * The result of an interactive sign-in: the access token plus the refresh token
 * used to renew it, and the access token's expiry.
 *
 * <p>Designed to be stored in a user's web session (it holds no transport or
 * live connection). A host app persists it after login and, because Entra rotates
 * refresh tokens, re-persists the rotated value whenever the token is refreshed
 * (see {@code RefreshingTokenProvider}'s onRefresh callback).
 *
 * <p>{@code toString()} is redacted so tokens never leak into logs or exceptions.
 *
 * @param accessToken  the bearer token
 * @param refreshToken the refresh token, or {@code null} if none was issued
 * @param expiresAt    when the access token expires
 */
public record TokenSet(String accessToken, String refreshToken, Instant expiresAt)
        implements java.io.Serializable {

    public static TokenSet expiringIn(String accessToken, String refreshToken, long expiresInSeconds,
            Instant now) {
        return new TokenSet(accessToken, refreshToken, now.plusSeconds(expiresInSeconds));
    }

    public boolean isValid(Instant now, Duration skew) {
        return now.plus(skew).isBefore(expiresAt);
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    @Override
    public String toString() {
        return "TokenSet{accessToken=<redacted>, refreshToken="
                + (hasRefreshToken() ? "<redacted>" : "null")
                + ", expiresAt=" + expiresAt + "}";
    }
}
