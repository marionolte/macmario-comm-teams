package com.macmario.services.comm.teams.client;

import com.macmario.services.comm.teams.TeamsException;
import com.macmario.services.comm.teams.http.HttpTransport;
import com.macmario.services.comm.teams.model.AdaptiveCard;
import java.net.URI;
import java.util.Objects;

/**
 * Sends notifications to a Teams channel through an incoming webhook URL.
 *
 * <p>This is the simplest send path: no Azure AD app, token, or admin consent is
 * needed — only the channel's webhook URL. It is send-only and channel-only;
 * for sending to users, reading messages, or richer scenarios use
 * {@link TeamsClient}.
 *
 * <p>Works with both legacy Office 365 connector webhooks and the newer Teams
 * Workflows webhooks (which reply with HTTP 202); any 2xx status is treated as
 * success. Note Microsoft is retiring the legacy connector webhooks in favor of
 * Workflows, so prefer a Workflows "when a webhook request is received" URL.
 */
public final class WebhookNotifier {

    private final HttpTransport transport;
    private final URI webhookUrl;

    public WebhookNotifier(HttpTransport transport, String webhookUrl) {
        this.transport = Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(webhookUrl, "webhookUrl");
        if (webhookUrl.isBlank()) {
            throw new TeamsException("webhookUrl must not be blank");
        }
        this.webhookUrl = URI.create(webhookUrl);
    }

    /** Sends a plain-text notification. */
    public void send(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be null or blank");
        }
        send(AdaptiveCard.builder().text(text).build());
    }

    /** Sends an Adaptive Card notification. */
    public void send(AdaptiveCard card) {
        Objects.requireNonNull(card, "card");
        var json = serialize(card);
        transport.postRaw(webhookUrl, "application/json", json);
    }

    private String serialize(AdaptiveCard card) {
        try {
            return transport.mapper().writeValueAsString(card.toEnvelope());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new TeamsException("Failed to serialize Adaptive Card", e);
        }
    }
}
