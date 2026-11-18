package com.workspace.keycloak.realm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.ws.rs.core.Response;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;

import com.workspace.keycloak.support.OidcHttp;
import com.workspace.keycloak.support.WorkspaceServerConfig;

/**
 * Valida o que o realm versionado entrega ao Grafana (T04 da spec 001 do grafana, ADR-0002): client confidencial
 * com PKCE, sem Direct Grant nem implícito, redirect exato, e o claim "roles" só com roles grafana-* e admin.
 */
@KeycloakIntegrationTest(config = WorkspaceServerConfig.class)
class GrafanaRealmIT {

    private static final String CLIENT_ID = "grafana";
    private static final String REDIRECT_URI = "http://localhost:3000/login/generic_oauth";

    @InjectRealm(fromJson = "/realm-workspace.json")
    ManagedRealm realm;

    @InjectHttpClient
    HttpClient httpClient;

    @Test
    void grafanaClient_IsConfidentialWithPkceExactRedirectAndNoPasswordGrantOrImplicit() {
        ClientRepresentation client = grafanaClient();

        assertThat(client.isPublicClient()).isFalse();
        assertThat(client.isStandardFlowEnabled()).isTrue();
        assertThat(client.isDirectAccessGrantsEnabled()).isFalse();
        assertThat(client.isImplicitFlowEnabled()).isFalse();
        assertThat(client.isServiceAccountsEnabled()).isFalse();
        assertThat(client.isFullScopeAllowed()).isFalse();
        assertThat(client.getRedirectUris()).containsExactly(REDIRECT_URI);
        assertThat(client.getAttributes())
                .containsEntry("pkce.code.challenge.method", "S256")
                .containsEntry("post.logout.redirect.uris", "http://localhost:3000/login");
    }

    @Test
    void grafanaClient_ScopeHasOnlyGrafanaRolesAndAdmin() {
        assertThat(realm.admin().clients().get(grafanaClient().getId()).getScopeMappings().realmLevel().listAll())
                .extracting(RoleRepresentation::getName)
                .containsExactlyInAnyOrder("admin", "grafana-viewer", "grafana-editor");
    }

    @Test
    void grafanaRoles_AreNeverDefaultRoles() {
        List<RoleRepresentation> defaults = realm.admin().roles().get("default-roles-workspace").getRealmRoleComposites()
                .stream().toList();

        assertThat(defaults).extracting(RoleRepresentation::getName)
                .doesNotContain("grafana-viewer", "grafana-editor", "admin");
    }

    // O claim "roles" leva só o que está no escopo do client: "user" (padrão) e roles de outras apps ficam de fora.
    @Test
    void grafanaToken_WhenUserHasGrafanaAndOtherRoles_CarriesOnlyGrafanaRolesInRolesClaim() {
        String userId = createUser("grafana.viewer");
        assign(userId, "grafana-viewer", "log-investigator");

        AccessToken token = realm.admin().clients().get(grafanaClient().getId()).clientScopesEvaluate()
                .generateAccessToken("openid", userId, null);

        assertThat(rolesClaim(token)).containsExactly("grafana-viewer");
    }

    @Test
    void grafanaToken_WhenUserIsAdmin_CarriesAdminRole() {
        String userId = createUser("grafana.admin");
        assign(userId, "admin");

        AccessToken token = realm.admin().clients().get(grafanaClient().getId()).clientScopesEvaluate()
                .generateAccessToken("openid", userId, null);

        assertThat(rolesClaim(token)).containsExactly("admin");
    }

    // Usuário final do Cadastro (só roles padrão) não ganha role grafana-*: o Grafana nega o login (strict).
    @Test
    void grafanaToken_WhenUserIsCadastroEndUser_HasNoGrafanaRole() {
        String userId = createUser("grafana.enduser");

        AccessToken token = realm.admin().clients().get(grafanaClient().getId()).clientScopesEvaluate()
                .generateAccessToken("openid", userId, null);

        assertThat(rolesClaim(token)).isEmpty();
        assertThat(realm.admin().users().get(userId).roles().realmLevel().listEffective())
                .extracting(RoleRepresentation::getName)
                .doesNotContain("grafana-viewer", "grafana-editor", "admin");
    }

    @Test
    void passwordGrant_ForGrafanaClient_IsRejected() throws Exception {
        OidcHttp.Response response =
                OidcHttp.passwordGrant(httpClient, realm.getBaseUrl(), CLIENT_ID, "any.user", "any-password");

        assertThat(response.status()).isBetween(400, 401);
        assertThat(response.body()).doesNotContain("access_token");
    }

    @Test
    void authorizationRequest_WithRedirectUriOutsideTheExactList_IsRejected() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), CLIENT_ID, "http://localhost:3000/login/generic_oauth/evil", true, "pt-BR");

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void authorizationRequest_WithoutPkce_IsRejected() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), CLIENT_ID, REDIRECT_URI, false, "pt-BR");

        assertThat(response.status()).isNotEqualTo(200);
    }

    @Test
    void authorizationRequest_WithExactRedirectUriAndPkce_ShowsLoginPage() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), CLIENT_ID, REDIRECT_URI, true, "pt-BR");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("kc-form-login");
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesClaim(AccessToken token) {
        Object claim = token.getOtherClaims().get("roles");
        return claim == null ? List.of() : (List<String>) claim;
    }

    private String createUser(String username) {
        UserRepresentation user = UserBuilder.create(username).enabled(true).build();
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
        return realm.admin().users().search(username, true).getFirst().getId();
    }

    private void assign(String userId, String... roleNames) {
        realm.admin().users().get(userId).roles().realmLevel().add(
                java.util.Arrays.stream(roleNames)
                        .map(name -> realm.admin().roles().get(name).toRepresentation()).toList());
    }

    private ClientRepresentation grafanaClient() {
        return realm.admin().clients().findByClientId(CLIENT_ID).getFirst();
    }
}
