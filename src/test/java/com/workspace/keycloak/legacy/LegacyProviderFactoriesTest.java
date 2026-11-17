package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

/** Factories do reset (T16) e do listener de conclusão (T17): IDs usados pelo realm e falha fechada sem config. */
class LegacyProviderFactoriesTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final KeycloakSession session = mock(KeycloakSession.class);

    private static Config.Scope validConfig() {
        Config.Scope config = mock(Config.Scope.class);
        when(config.get(LegacyClientSettings.OPTION_BASE_URL)).thenReturn("https://localhost:1");
        when(config.get(LegacyClientSettings.OPTION_KEY_ID)).thenReturn("keycloak-1");
        when(config.get(LegacyClientSettings.OPTION_SIGNING_KEY)).thenReturn(KEY);
        when(config.get(LegacyClientSettings.OPTION_CA_FILE)).thenReturn("/opt/keycloak/conf/legado-ca.pem");
        when(config.getInt(LegacyClientSettings.OPTION_TIMEOUT_MS, 2000)).thenReturn(2000);
        return config;
    }

    private static Config.Scope emptyConfig() {
        Config.Scope config = mock(Config.Scope.class);
        when(config.getInt(anyString(), anyInt())).thenReturn(2000);
        return config;
    }

    @Test
    void resetFactory_HasTheIdUsedByTheRealmFlow() {
        assertThat(new LegacyResetChooseUserFactory().getId()).isEqualTo("legacy-reset-choose-user");
        assertThat(new LegacyResetChooseUserFactory().getDisplayType()).isEqualTo("Legacy Choose User");
    }

    @Test
    void resetFactory_CreatesTheAuthenticatorWithOrWithoutConfig() {
        LegacyResetChooseUserFactory configured = new LegacyResetChooseUserFactory();
        configured.init(validConfig());
        LegacyResetChooseUserFactory missing = new LegacyResetChooseUserFactory();
        missing.init(emptyConfig());

        assertThat(configured.create(session)).isInstanceOf(LegacyResetChooseUser.class);
        assertThat(missing.create(session)).isInstanceOf(LegacyResetChooseUser.class);
        assertThat(configured.getRequirementChoices()).isNotEmpty();
    }

    @Test
    void resetFactory_DeclaresAllOptionsAndMarksTheKeyAsSecret() {
        var properties = new LegacyResetChooseUserFactory().getConfigMetadata();

        assertThat(properties).extracting(ProviderConfigProperty::getName).containsExactlyInAnyOrder("base-url",
                "timeout-ms", "key-id", "signing-key", "ca-file");
        assertThat(properties).filteredOn(p -> "signing-key".equals(p.getName()))
                .singleElement().satisfies(p -> assertThat(p.isSecret()).isTrue());
    }

    @Test
    void listenerFactory_HasTheIdUsedByTheRealm() {
        assertThat(new LegacyMigrationCompletionListenerFactory().getId()).isEqualTo("legacy-migration-completion");
    }

    @Test
    void listenerFactory_CreatesTheListenerWithOrWithoutConfig() {
        LegacyMigrationCompletionListenerFactory configured = new LegacyMigrationCompletionListenerFactory();
        configured.init(validConfig());
        LegacyMigrationCompletionListenerFactory missing = new LegacyMigrationCompletionListenerFactory();
        missing.init(emptyConfig());

        assertThat(configured.create(session)).isInstanceOf(LegacyMigrationCompletionListener.class);
        assertThat(missing.create(session)).isInstanceOf(LegacyMigrationCompletionListener.class);
    }
}
