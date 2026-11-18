package com.macmario.services.comm.teams.config;

import com.macmario.services.comm.teams.TeamsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Immutable configuration for the comm.teams library.
 *
 * <p>No secret is ever hardcoded: build a config programmatically (the typical
 * path for a host web application), from environment variables
 * ({@link #fromEnv()}), or from a {@code .properties} file
 * ({@link #fromProperties(Path)}).
 *
 * <p>Not every field is required for every operation. Posting through an
 * incoming webhook needs only {@link #webhookUrl()}; Microsoft Graph calls need
 * {@link #graphBaseUrl()} plus a token source; the client-credentials token
 * provider additionally needs {@link #tenantId()}, {@link #clientId()} and
 * {@link #clientSecret()}. The {@code require*} methods fail fast with a clear
 * message when a needed field is missing.
 */
public record TeamsConfig(
        String graphBaseUrl,
        String authority,
        String tenantId,
        String clientId,
        String clientSecret,
        String scope,
        String webhookUrl,
        String defaultTeamId,
        String defaultChannelId,
        String clientState,
        String redirectUri,
        String username,
        String password) {

    public static final String DEFAULT_GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0";
    public static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com";
    public static final String DEFAULT_SCOPE = "https://graph.microsoft.com/.default";

    /** OAuth2 token endpoint for this tenant. */
    public String tokenEndpoint() {
        return authority + "/" + requireTenant() + "/oauth2/v2.0/token";
    }

    /** OAuth2 device-code endpoint for this tenant. */
    public String deviceCodeEndpoint() {
        return authority + "/" + requireTenant() + "/oauth2/v2.0/devicecode";
    }

    /** OAuth2 authorization endpoint for this tenant (user sign-in). */
    public String authorizeEndpoint() {
        return authority + "/" + requireTenant() + "/oauth2/v2.0/authorize";
    }

    public String requireTenant() {
        return required(tenantId, "tenantId");
    }

    public String requireWebhookUrl() {
        return required(webhookUrl, "webhookUrl");
    }

    public String requireRedirectUri() {
        return required(redirectUri, "redirectUri");
    }

    /** Validates the fields required for the username/password (ROPC) login. */
    public TeamsConfig requireResourceOwnerCredentials() {
        required(tenantId, "tenantId");
        required(clientId, "clientId");
        required(username, "username");
        required(password, "password");
        // ROPC is not allowed on the multiplexed endpoints.
        if (tenantId.equalsIgnoreCase("common") || tenantId.equalsIgnoreCase("consumers")) {
            throw new TeamsException("ROPC requires a specific tenant or 'organizations', not '"
                    + tenantId + "'");
        }
        return this;
    }

    /** Redacts the client secret and webhook URL so configs are safe to log. */
    @Override
    public String toString() {
        return "TeamsConfig{graphBaseUrl=" + graphBaseUrl + ", authority=" + authority
                + ", tenantId=" + tenantId + ", clientId=" + clientId
                + ", clientSecret=" + (clientSecret == null ? "null" : "<redacted>")
                + ", scope=" + scope
                + ", webhookUrl=" + (webhookUrl == null ? "null" : "<redacted>")
                + ", defaultTeamId=" + defaultTeamId + ", defaultChannelId=" + defaultChannelId
                + ", clientState=" + (clientState == null ? "null" : "<redacted>")
                + ", redirectUri=" + redirectUri
                + ", username=" + username
                + ", password=" + (password == null ? "null" : "<redacted>") + "}";
    }

    /** Validates the fields required for the client-credentials OAuth2 flow. */
    public TeamsConfig requireClientCredentials() {
        required(tenantId, "tenantId");
        required(clientId, "clientId");
        required(clientSecret, "clientSecret");
        return this;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new TeamsException("Missing required configuration value: " + name);
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads configuration from environment variables: {@code TEAMS_TENANT_ID},
     * {@code TEAMS_CLIENT_ID}, {@code TEAMS_CLIENT_SECRET}, {@code TEAMS_SCOPE},
     * {@code TEAMS_WEBHOOK_URL}, {@code TEAMS_TEAM_ID}, {@code TEAMS_CHANNEL_ID},
     * {@code TEAMS_CLIENT_STATE}, {@code TEAMS_GRAPH_BASE_URL},
     * {@code TEAMS_AUTHORITY}.
     */
    public static TeamsConfig fromEnv() {
        return fromEnv(System::getenv);
    }

    /** Testable variant of {@link #fromEnv()} with an injectable lookup. */
    public static TeamsConfig fromEnv(java.util.function.Function<String, String> env) {
        return builder()
                .graphBaseUrl(env.apply("TEAMS_GRAPH_BASE_URL"))
                .authority(env.apply("TEAMS_AUTHORITY"))
                .tenantId(env.apply("TEAMS_TENANT_ID"))
                .clientId(env.apply("TEAMS_CLIENT_ID"))
                .clientSecret(env.apply("TEAMS_CLIENT_SECRET"))
                .scope(env.apply("TEAMS_SCOPE"))
                .webhookUrl(env.apply("TEAMS_WEBHOOK_URL"))
                .defaultTeamId(env.apply("TEAMS_TEAM_ID"))
                .defaultChannelId(env.apply("TEAMS_CHANNEL_ID"))
                .clientState(env.apply("TEAMS_CLIENT_STATE"))
                .redirectUri(env.apply("TEAMS_REDIRECT_URI"))
                .username(env.apply("TEAMS_USERNAME"))
                .password(env.apply("TEAMS_PASSWORD"))
                .build();
    }

    /**
     * Reads configuration from a {@code .properties} file using keys such as
     * {@code teams.tenantId}, {@code teams.clientId}, {@code teams.clientSecret},
     * {@code teams.webhookUrl}, {@code teams.teamId}, {@code teams.channelId}.
     */
    public static TeamsConfig fromProperties(Path file) {
        var props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (java.io.IOException e) {
            throw new TeamsException("Failed to read config file: " + file, e);
        }
        return fromProperties(props);
    }

    /**
     * Builds a config from a {@link ConfigBackend} (e.g. the configdb). Delegates to
     * {@link #fromProperties(Properties)} so the {@code teams.*} key mapping lives in one
     * place; password values are already decrypted by the backend.
     */
    public static TeamsConfig fromConfigBackend(ConfigBackend backend) {
        return fromProperties(backend.getAll());
    }

    public static TeamsConfig fromProperties(Properties props) {
        return builder()
                .graphBaseUrl(props.getProperty("teams.graphBaseUrl"))
                .authority(props.getProperty("teams.authority"))
                .tenantId(props.getProperty("teams.tenantId"))
                .clientId(props.getProperty("teams.clientId"))
                .clientSecret(props.getProperty("teams.clientSecret"))
                .scope(props.getProperty("teams.scope"))
                .webhookUrl(props.getProperty("teams.webhookUrl"))
                .defaultTeamId(props.getProperty("teams.teamId"))
                .defaultChannelId(props.getProperty("teams.channelId"))
                .clientState(props.getProperty("teams.clientState"))
                .redirectUri(props.getProperty("teams.redirectUri"))
                .username(props.getProperty("teams.username"))
                .password(props.getProperty("teams.password"))
                .build();
    }

    /** Builder that applies sensible defaults for blank optional values. */
    public static final class Builder {
        private String graphBaseUrl;
        private String authority;
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private String scope;
        private String webhookUrl;
        private String defaultTeamId;
        private String defaultChannelId;
        private String clientState;
        private String redirectUri;
        private String username;
        private String password;

        public Builder graphBaseUrl(String v) { this.graphBaseUrl = v; return this; }
        public Builder authority(String v) { this.authority = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder clientId(String v) { this.clientId = v; return this; }
        public Builder clientSecret(String v) { this.clientSecret = v; return this; }
        public Builder scope(String v) { this.scope = v; return this; }
        public Builder webhookUrl(String v) { this.webhookUrl = v; return this; }
        public Builder defaultTeamId(String v) { this.defaultTeamId = v; return this; }
        public Builder defaultChannelId(String v) { this.defaultChannelId = v; return this; }
        public Builder clientState(String v) { this.clientState = v; return this; }
        public Builder redirectUri(String v) { this.redirectUri = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder password(String v) { this.password = v; return this; }

        public TeamsConfig build() {
            return new TeamsConfig(
                    orDefault(graphBaseUrl, DEFAULT_GRAPH_BASE_URL),
                    orDefault(authority, DEFAULT_AUTHORITY),
                    blankToNull(tenantId),
                    blankToNull(clientId),
                    blankToNull(clientSecret),
                    orDefault(scope, DEFAULT_SCOPE),
                    blankToNull(webhookUrl),
                    blankToNull(defaultTeamId),
                    blankToNull(defaultChannelId),
                    blankToNull(clientState),
                    blankToNull(redirectUri),
                    blankToNull(username),
                    blankToNull(password));
        }

        private static String orDefault(String value, String fallback) {
            return (value == null || value.isBlank()) ? fallback : value.strip();
        }

        private static String blankToNull(String value) {
            return (value == null || value.isBlank()) ? null : value.strip();
        }
    }
}
