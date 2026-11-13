package com.macmario.services.comm.teams.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.macmario.services.comm.teams.TeamsException;
import java.time.Instant;

/** Parses OAuth2 token-endpoint JSON responses into {@link TokenSet} values. */
final class TokenSets {

    private TokenSets() {
    }

    /**
     * Builds a {@link TokenSet} from a token response. If the response omits a
     * refresh token (common on refresh when rotation is off), {@code fallbackRefresh}
     * is retained.
     */
    static TokenSet from(JsonNode response, String fallbackRefresh, Instant now) {
        var accessToken = text(response, "access_token");
        if (accessToken == null) {
            throw new TeamsException("Token response contained no access_token");
        }
        var refresh = text(response, "refresh_token");
        var expiresIn = response == null ? 3600L : response.path("expires_in").asLong(3600L);
        return TokenSet.expiringIn(accessToken, refresh != null ? refresh : fallbackRefresh, expiresIn, now);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
