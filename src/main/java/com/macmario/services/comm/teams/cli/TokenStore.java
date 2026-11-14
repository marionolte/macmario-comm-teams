package com.macmario.services.comm.teams.cli;

import com.macmario.services.comm.teams.auth.TokenSet;
import java.util.Optional;

/**
 * Persists a signed-in user's {@link TokenSet} between CLI invocations, so commands
 * after {@code user-login} run without prompting again.
 *
 * <p>Implementations hold a long-lived refresh token and must store it securely.
 */
public interface TokenStore {

    /** @return the stored token set, or empty if none is stored (or it is unreadable). */
    Optional<TokenSet> load();

    /** Persists (overwriting) the token set. */
    void save(TokenSet tokenSet);

    /**
     * Removes any stored token.
     *
     * @return {@code true} if a stored token existed and was removed
     */
    boolean clear();

    /** A human-readable description of where tokens are stored (for CLI messages). */
    String location();
}
