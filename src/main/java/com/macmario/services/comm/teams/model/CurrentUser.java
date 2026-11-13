package com.macmario.services.comm.teams.model;

/**
 * The signed-in user, from Microsoft Graph {@code GET /me}. Useful right after a
 * login to identify who authenticated.
 *
 * @param id                 the user's object id
 * @param displayName        the user's display name, or {@code null}
 * @param userPrincipalName  the user's UPN (sign-in name), or {@code null}
 */
public record CurrentUser(String id, String displayName, String userPrincipalName)
        implements java.io.Serializable {
}
