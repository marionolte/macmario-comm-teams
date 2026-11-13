package com.macmario.services.comm.teams.notifications;

/**
 * A single Microsoft Graph change notification delivered to a webhook endpoint.
 *
 * @param subscriptionId the subscription that produced this notification
 * @param changeType     {@code created}, {@code updated}, or {@code deleted}
 * @param resource       the Graph resource path of the changed item
 * @param resourceId     the changed item's id (from {@code resourceData.id}), or {@code null}
 * @param clientState    the opaque state echoed back by Graph; verify it before trusting the payload
 * @param tenantId       the tenant the change occurred in, or {@code null}
 */
public record ChangeNotification(
        String subscriptionId,
        String changeType,
        String resource,
        String resourceId,
        String clientState,
        String tenantId) {

    /**
     * @return {@code true} if {@code clientState} equals the expected secret, using
     *         a length-constant comparison. Always call this before acting on a
     *         notification to reject spoofed callbacks.
     */
    public boolean hasValidClientState(String expected) {
        if (expected == null || clientState == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                clientState.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
