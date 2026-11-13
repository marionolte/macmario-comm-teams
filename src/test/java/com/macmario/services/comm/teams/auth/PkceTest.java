package com.macmario.services.comm.teams.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PkceTest {

    @Test
    @DisplayName("S256 challenge = base64url(SHA-256(ASCII(verifier))) per RFC 7636 §4.2")
    void s256Vector() {
        // Verifier string from RFC 7636 Appendix B. Challenge per §4.2 (hash of the
        // ASCII verifier string, not the raw octet array in the appendix's example),
        // verified independently with openssl and python.
        var verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        var expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expected, Pkce.challengeFor(verifier));
    }

    @Test
    @DisplayName("generated verifier is 43 url-safe chars and the challenge is derived from it")
    void generate_producesValidPair() {
        var pair = Pkce.generate();
        assertEquals(43, pair.codeVerifier().length());
        assertTrue(pair.codeVerifier().matches("[A-Za-z0-9_-]+"), "verifier must be base64url");
        assertEquals(Pkce.challengeFor(pair.codeVerifier()), pair.codeChallenge());
        assertEquals("S256", pair.challengeMethod());
    }

    @Test
    @DisplayName("each generation is unique")
    void generate_unique() {
        assertNotEquals(Pkce.generate().codeVerifier(), Pkce.generate().codeVerifier());
        assertNotEquals(Pkce.newState(), Pkce.newState());
    }
}
