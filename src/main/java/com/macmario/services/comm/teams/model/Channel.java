package com.macmario.services.comm.teams.model;

/**
 * A channel within a team (from {@code GET /teams/{id}/channels}).
 *
 * @param id          the channel id (e.g. {@code 19:...@thread.tacv2})
 * @param displayName the channel's display name (e.g. "General")
 */
public record Channel(String id, String displayName) {
}
