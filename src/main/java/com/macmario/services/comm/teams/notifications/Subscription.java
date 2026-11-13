package com.macmario.services.comm.teams.notifications;

import java.time.Instant;

/**
 * A Microsoft Graph change-notification subscription.
 *
 * @param id             the subscription id (used to renew or delete it)
 * @param resource       the subscribed resource path
 * @param expiration     when the subscription expires and must be renewed, or {@code null} if unparseable
 */
public record Subscription(String id, String resource, Instant expiration) {
}
