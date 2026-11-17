package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

class LegacyUsernamePasswordFormFactoryTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final KeycloakSession session = mock(KeycloakSession.class);

    @Test
    void getId_IsTheProviderIdUsedByTheFlows() {
        assertThat(new LegacyUsernamePasswordFormFactory().getId()).isEqualTo("legacy-username-password-form");
    }

    @Test
    void create_WhenConfigIsValid_ReturnsFormWithClient() {
        LegacyUsernamePasswordFormFactory factory = new LegacyUsernamePasswordFormFactory();
        Config.Scope config = mock(Config.Scope.class);
        when(config.get(LegacyClientSettings.OPTION_BASE_URL)).thenReturn("https://localhost:1");
        when(config.get(LegacyClientSettings.OPTION_KEY_ID)).thenReturn("keycloak-1");
        when(config.get(LegacyClientSettings.OPTION_SIGNING_KEY)).thenReturn(KEY);
        when(config.get(LegacyClientSettings.OPTION_CA_FILE)).thenReturn("/opt/keycloak/conf/legado-ca.pem");
        when(config.getInt(LegacyClientSettings.OPTION_TIMEOUT_MS, 2000)).thenReturn(2000);

        factory.init(config);
        Authenticator form = factory.create(session);

        assertThat(form).isInstanceOf(LegacyUsernamePasswordForm.class);
    }

    @Test
    void init_WhenConfigIsMissing_DoesNotThrowAndStillCreatesTheStandardBehaviourForm() {
        LegacyUsernamePasswordFormFactory factory = new LegacyUsernamePasswordFormFactory();
        Config.Scope config = mock(Config.Scope.class);
        when(config.getInt(anyString(), anyInt())).thenReturn(2000);

        factory.init(config);

        assertThat(factory.create(session)).isInstanceOf(LegacyUsernamePasswordForm.class);
    }

    @Test
    void getConfigMetadata_DeclaresAllOptionsAndMarksTheKeyAsSecret() {
        var properties = new LegacyUsernamePasswordFormFactory().getConfigMetadata();

        assertThat(properties).extracting(ProviderConfigProperty::getName).containsExactlyInAnyOrder("base-url",
                "timeout-ms", "key-id", "signing-key", "ca-file");
        assertThat(properties).filteredOn(p -> p.getName().equals("signing-key")).singleElement()
                .satisfies(p -> assertThat(p.isSecret()).isTrue());
    }
}
