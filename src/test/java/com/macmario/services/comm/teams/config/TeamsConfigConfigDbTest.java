package com.macmario.services.comm.teams.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.macmario.services.comm.teams.support.InMemoryConfigBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamsConfigConfigDbTest {

    @Test
    @DisplayName("fromConfigBackend maps teams.* keys including username/password")
    void fromConfigBackend_maps() {
        var backend = new InMemoryConfigBackend();
        backend.upsert("teams.tenantId", "tid", false);
        backend.upsert("teams.clientId", "cid", false);
        backend.upsert("teams.username", "user@contoso.com", false);
        backend.upsert("teams.password", "s3cret", true);
        backend.upsert("teams.webhookUrl", "https://hook/x", false);

        var config = TeamsConfig.fromConfigBackend(backend);

        assertEquals("tid", config.tenantId());
        assertEquals("cid", config.clientId());
        assertEquals("user@contoso.com", config.username());
        assertEquals("s3cret", config.password());
        assertEquals("https://hook/x", config.webhookUrl());
    }

    @Test
    @DisplayName("requireResourceOwnerCredentials passes when user/pass/tenant/client set")
    void requireRopc_ok() {
        var config = TeamsConfig.builder()
                .tenantId("tid").clientId("cid").username("u").password("p").build();
        // Should not throw.
        assertEquals(config, config.requireResourceOwnerCredentials());
    }

    @Test
    @DisplayName("requireResourceOwnerCredentials rejects the 'common' endpoint")
    void requireRopc_rejectsCommon() {
        var config = TeamsConfig.builder()
                .tenantId("common").clientId("cid").username("u").password("p").build();
        org.junit.jupiter.api.Assertions.assertThrows(
                com.macmario.services.comm.teams.TeamsException.class,
                config::requireResourceOwnerCredentials);
    }

    @Test
    @DisplayName("toString redacts the password")
    void toString_redactsPassword() {
        var config = TeamsConfig.builder().username("u").password("topsecret").build();
        var s = config.toString();
        assertEquals(false, s.contains("topsecret"));
        assertEquals(true, s.contains("username=u"));
    }
}
