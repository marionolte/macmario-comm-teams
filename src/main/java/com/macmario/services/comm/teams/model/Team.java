package com.macmario.services.comm.teams.model;

/**
 * A team the signed-in user is a member of (from {@code GET /me/joinedTeams}).
 *
 * @param id          the team id (also the backing group id)
 * @param displayName the team's display name
 */
public record Team(String id, String displayName) {
}
