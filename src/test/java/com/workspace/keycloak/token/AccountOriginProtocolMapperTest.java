package com.workspace.keycloak.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;

class AccountOriginProtocolMapperTest {

    private final AccountOriginProtocolMapper mapper = new AccountOriginProtocolMapper();

    // A terceira linha: o importador marca o Legado como migrado, mas a conta continua importada.
    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "line-1, -,         imported",
            "-,      legacy-id, migrated",
            "line-1, legacy-id, imported",
            "' ',    legacy-id, migrated",
    })
    void accessToken_CarriesTheOriginDerivedFromTheAdminOnlyAttributes(String importLineId, String legacyUserId,
            String expected) {
        AccessToken token = accessToken(importLineId, legacyUserId, config("true"));

        assertThat(token.getOtherClaims()).containsExactly(Map.entry("account_origin", expected));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {"-, -", "'', ' '"})
    void accessToken_WhenAccountHasNeitherOrigin_HasNoClaim(String importLineId, String legacyUserId) {
        assertThat(accessToken(importLineId, legacyUserId, config("true")).getOtherClaims()).isEmpty();
    }

    @Test
    void accessToken_WhenMapperIsNotIncludedInAccessTokens_HasNoClaim() {
        assertThat(accessToken("line-1", null, config("false")).getOtherClaims()).isEmpty();
    }

    @Test
    void idToken_CarriesTheClaim() {
        UserSessionModel session = session(null, "legacy-id");

        IDToken token = mapper.transformIDToken(new IDToken(), config("true"), keycloakSession(),
                session, mock(ClientSessionContext.class));

        assertThat(token.getOtherClaims()).containsEntry("account_origin", "migrated");
    }

    @Test
    void provider_IsRegisteredWithTheClaimNameAndTokenSwitches() {
        assertThat(mapper.getId()).isEqualTo("workspace-account-origin-mapper");
        assertThat(mapper.getDisplayCategory()).isNotBlank();
        assertThat(mapper.getDisplayType()).isNotBlank();
        assertThat(mapper.getHelpText()).isNotBlank();
        assertThat(mapper.getConfigProperties()).extracting(ProviderConfigProperty::getName)
                .contains("claim.name", "id.token.claim", "access.token.claim", "userinfo.token.claim");
    }

    private AccessToken accessToken(String importLineId, String legacyUserId, ProtocolMapperModel config) {
        UserSessionModel session = session(importLineId, legacyUserId);
        return mapper.transformAccessToken(new AccessToken(), config, keycloakSession(),
                session, mock(ClientSessionContext.class));
    }

    private static UserSessionModel session(String importLineId, String legacyUserId) {
        UserModel user = mock(UserModel.class);
        when(user.getFirstAttribute("importLineId")).thenReturn(importLineId);
        when(user.getFirstAttribute("legacyUserId")).thenReturn(legacyUserId);
        UserSessionModel session = mock(UserSessionModel.class);
        when(session.getUser()).thenReturn(user);
        return session;
    }

    // O mapper base consulta o client do contexto antes de montar o claim.
    private static KeycloakSession keycloakSession() {
        return mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
    }

    private static ProtocolMapperModel config(String include) {
        ProtocolMapperModel model = new ProtocolMapperModel();
        Map<String, String> config = new HashMap<>();
        config.put("claim.name", "account_origin");
        config.put("access.token.claim", include);
        config.put("id.token.claim", include);
        model.setConfig(config);
        return model;
    }
}
