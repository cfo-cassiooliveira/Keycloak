package com.workspace.keycloak.support;

import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/**
 * Keycloak de teste com a migração do Legado ligada: o authenticator {@code legacy-username-password-form}, o
 * {@code legacy-reset-choose-user} e o listener {@code legacy-migration-completion} apontam para o
 * {@link LegadoTestServer} e a auditoria vai ao {@link FakeGraylog}, onde os testes conferem os eventos.
 */
public class LegacyServerConfig implements KeycloakServerConfig {

    private static final String AUTHENTICATOR = "authenticator";
    private static final String EVENTS_LISTENER = "events-listener";
    private static final String LOGIN_FORM = "legacy-username-password-form";
    private static final String RESET_CHOOSE_USER = "legacy-reset-choose-user";
    private static final String COMPLETION = "legacy-migration-completion";

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        FakeGraylog graylog = FakeGraylog.instance();
        KeycloakServerConfigBuilder builder = config.dependencyCurrentProject();
        legadoClient(builder, AUTHENTICATOR, LOGIN_FORM);
        legadoClient(builder, AUTHENTICATOR, RESET_CHOOSE_USER);
        legadoClient(builder, EVENTS_LISTENER, COMPLETION);
        return builder
                .spiOption(EVENTS_LISTENER, "workspace-audit", "graylog-url", graylog.gelfUrl())
                .spiOption(EVENTS_LISTENER, "workspace-audit", "graylog-timeout-ms", "1000")
                .spiOption(EVENTS_LISTENER, "workspace-audit", "fallback-file", graylog.fallbackFile().toString())
                .spiOption(EVENTS_LISTENER, "workspace-audit", "environment", "test");
    }

    private static void legadoClient(KeycloakServerConfigBuilder config, String spi, String provider) {
        config.spiOption(spi, provider, "base-url", "https://localhost:" + LegadoTestServer.instance().port())
                .spiOption(spi, provider, "ca-file", LegadoTestServer.caFile().toString())
                .spiOption(spi, provider, "timeout-ms", "1000")
                .spiOption(spi, provider, "key-id", LegadoTestServer.KEY_ID)
                .spiOption(spi, provider, "signing-key", LegadoTestServer.SIGNING_KEY);
    }
}
