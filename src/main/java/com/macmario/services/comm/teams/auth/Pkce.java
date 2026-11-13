package com.macmario.services.comm.teams.auth;

import com.macmario.services.comm.teams.TeamsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (RFC 7636) and anti-CSRF state generation for the authorization-code flow.
 *
 * <p>All values use base64url-without-padding encoding. The code challenge is the
 * S256 transform of the verifier: {@code base64url(SHA-256(verifier))}.
 */
public final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {
    }

    /** A PKCE code verifier (43 chars from 32 random bytes) and its S256 challenge. */
    public record Pair(String codeVerifier, String codeChallenge) {
        public String challengeMethod() {
            return "S256";
        }
    }

    public static Pair generate() {
        var verifier = randomUrlToken(32);
        return new Pair(verifier, challengeFor(verifier));
    }

    /** Computes the S256 code challenge for an existing verifier (used in tests). */
    public static String challengeFor(String codeVerifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new TeamsException("SHA-256 is not available", e);
        }
    }

    /** A random anti-CSRF state value (128 bits, base64url). */
    public static String newState() {
        return randomUrlToken(16);
    }

    private static String randomUrlToken(int numBytes) {
        var bytes = new byte[numBytes];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
