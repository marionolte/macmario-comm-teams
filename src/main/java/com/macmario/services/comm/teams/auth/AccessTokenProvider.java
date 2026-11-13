package com.macmario.services.comm.teams.auth;

/**
 * Supplies a valid Microsoft Graph bearer token on demand.
 *
 * <p>Implementations must be thread-safe and are expected to cache and refresh
 * transparently. The appropriate implementation depends on the operation:
 *
 * <ul>
 *   <li>{@link StaticTokenProvider} — the host web application has already
 *       obtained a <em>delegated</em> user token (via its own OAuth sign-in) and
 *       supplies it here. This is the required path for <em>sending</em> channel
 *       and chat messages, which Microsoft Graph only permits with delegated
 *       permissions (app-only tokens are allowed solely for migration).</li>
 *   <li>{@link ClientCredentialTokenProvider} — app-only token for
 *       <em>reading</em> messages and managing change-notification subscriptions
 *       (both require admin-consented, protected application permissions).</li>
 *   <li>{@link DeviceCodeTokenProvider} — interactive delegated sign-in for the
 *       command-line {@code Main} driver.</li>
 * </ul>
 */
@FunctionalInterface
public interface AccessTokenProvider {

    /**
     * @return a currently valid bearer token value, refreshing if necessary.
     * @throws com.macmario.services.comm.teams.TeamsException if a token cannot be obtained
     */
    String token();
}
