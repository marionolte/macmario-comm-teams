package com.macmario.services.comm.teams.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.macmario.services.comm.teams.TeamsException;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamsConfigTest {

    @Test
    @DisplayName("builder applies defaults for blank optional fields")
    void builder_appliesDefaults() {
        var config = TeamsConfig.builder().tenantId("tid").build();

        assertEquals(TeamsConfig.DEFAULT_GRAPH_BASE_URL, config.graphBaseUrl());
        assertEquals(TeamsConfig.DEFAULT_AUTHORITY, config.authority());
        assertEquals(TeamsConfig.DEFAULT_SCOPE, config.scope());
        assertNull(config.clientId());
    }

    @Test
    @DisplayName("tokenEndpoint is built from authority and tenant")
    void tokenEndpoint_built() {
        var config = TeamsConfig.builder().tenantId("tid").build();
        assertEquals("https://login.microsoftonline.com/tid/oauth2/v2.0/token", config.tokenEndpoint());
        assertEquals("https://login.microsoftonline.com/tid/oauth2/v2.0/devicecode", config.deviceCodeEndpoint());
    }

    @Test
    @DisplayName("requireClientCredentials throws when secret is missing")
    void requireClientCredentials_missing_throws() {
        var config = TeamsConfig.builder().tenantId("tid").clientId("cid").build();
        var ex = assertThrows(TeamsException.class, config::requireClientCredentials);
        assertEquals(true, ex.getMessage().contains("clientSecret"));
    }

    @Test
    @DisplayName("fromEnv reads known variables and trims blanks to null")
    void fromEnv_reads() {
        Map<String, String> env = Map.of(
                "TEAMS_TENANT_ID", "tid",
                "TEAMS_CLIENT_ID", "cid",
                "TEAMS_CLIENT_SECRET", "secret",
                "TEAMS_WEBHOOK_URL", "https://hook.example/x");

        var config = TeamsConfig.fromEnv(env::get);

        assertEquals("tid", config.tenantId());
        assertEquals("cid", config.clientId());
        assertEquals("secret", config.clientSecret());
        assertEquals("https://hook.example/x", config.webhookUrl());
        assertNull(config.defaultTeamId());
    }

    @Test
    @DisplayName("fromProperties maps teams.* keys")
    void fromProperties_maps() {
        var props = new Properties();
        props.setProperty("teams.tenantId", "tid");
        props.setProperty("teams.teamId", "team-1");
        props.setProperty("teams.channelId", "chan-1");

        var config = TeamsConfig.fromProperties(props);

        assertEquals("tid", config.tenantId());
        assertEquals("team-1", config.defaultTeamId());
        assertEquals("chan-1", config.defaultChannelId());
    }

    @Test
    @DisplayName("requireWebhookUrl throws when absent")
    void requireWebhookUrl_absent_throws() {
        var config = TeamsConfig.builder().build();
        assertThrows(TeamsException.class, config::requireWebhookUrl);
    }
}
