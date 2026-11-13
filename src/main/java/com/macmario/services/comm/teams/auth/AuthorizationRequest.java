package com.macmario.services.comm.teams.auth;

/**
 * The in-flight state of an authorization-code sign-in, produced by
 * {@link AuthorizationCodeFlow#start}. The host app redirects the user to
 * {@link #authorizationUrl()} and stores the rest in the user's session until the
 * browser returns to the redirect URI, then passes it to
 * {@link AuthorizationCodeFlow#exchange}.
 *
 * <p>{@code toString()} is redacted: the code verifier is a secret.
 *
 * @param authorizationUrl where to send the user's browser to sign in
 * @param state            anti-CSRF value; must match the {@code state} on the callback
 * @param codeVerifier     PKCE verifier; secret, never sent to the browser
 * @param redirectUri      the exact redirect URI used (must be byte-identical on exchange)
 */
public record AuthorizationRequest(
        String authorizationUrl,
        String state,
        String codeVerifier,
        String redirectUri) implements java.io.Serializable {

    @Override
    public String toString() {
        return "AuthorizationRequest{authorizationUrl=" + authorizationUrl
                + ", state=" + state + ", codeVerifier=<redacted>, redirectUri=" + redirectUri + "}";
    }
}
