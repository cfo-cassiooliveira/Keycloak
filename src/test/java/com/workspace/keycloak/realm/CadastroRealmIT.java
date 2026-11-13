package com.workspace.keycloak.realm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.ws.rs.core.Response;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;
import org.keycloak.util.JsonSerialization;

import com.workspace.keycloak.support.OidcHttp;
import com.workspace.keycloak.support.WorkspaceServerConfig;

/**
 * Valida o que o realm versionado entrega ao Cadastro (T04 da spec 001 do cadastro): client confidencial com PKCE,
 * conta de serviço com o mínimo para a Admin API, roles, reset de senha, SMTP e o User Profile do provisioner.
 */
@KeycloakIntegrationTest(config = WorkspaceServerConfig.class)
class CadastroRealmIT {

    private static final String CLIENT_ID = "cadastro";
    private static final String REDIRECT_URI = "http://localhost:4300/bff/signin-oidc";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\":\"([^\"]+)\"");

    @InjectRealm(fromJson = "/realm-workspace.json")
    ManagedRealm realm;

    @InjectHttpClient
    HttpClient httpClient;

    @InjectKeycloakUrls
    KeycloakUrls keycloakUrls;

    @Test
    void cadastroClient_IsConfidentialWithPkceExactRedirectAndNoPasswordGrant() {
        ClientRepresentation client = cadastroClient();

        assertThat(client.isPublicClient()).isFalse();
        assertThat(client.isStandardFlowEnabled()).isTrue();
        assertThat(client.isDirectAccessGrantsEnabled()).isFalse();
        assertThat(client.isImplicitFlowEnabled()).isFalse();
        assertThat(client.isServiceAccountsEnabled()).isTrue();
        assertThat(client.isFullScopeAllowed()).isFalse();
        assertThat(client.getRedirectUris()).containsExactly(REDIRECT_URI);
        assertThat(client.getAttributes())
                .containsEntry("pkce.code.challenge.method", "S256")
                .containsEntry("post.logout.redirect.uris", "http://localhost:4300/");
    }

    // Minimização (LGPD): tokens do Cadastro só com id, login (mapper próprio) e roles; sem profile/email.
    @Test
    void cadastroClient_TokensCarryOnlyLoginAndRoles() {
        ClientRepresentation client = cadastroClient();

        assertThat(client.getDefaultClientScopes()).containsExactlyInAnyOrder("basic", "acr", "roles");
        assertThat(client.getOptionalClientScopes()).containsExactly("kc-admin-api");
        assertThat(client.getProtocolMappers()).singleElement().satisfies(mapper -> {
            assertThat(mapper.getProtocolMapper()).isEqualTo("oidc-usermodel-property-mapper");
            assertThat(mapper.getConfig()).containsEntry("user.attribute", "username")
                    .containsEntry("claim.name", "preferred_username");
        });
    }

    @Test
    void authorizationRequest_WithRedirectUriOutsideTheExactList_IsRejected() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), CLIENT_ID, "http://localhost:4300/bff/signin-oidc/evil", true, "pt-BR");

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void authorizationRequest_WithExactRedirectUriAndPkce_ShowsLoginPage() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), CLIENT_ID, REDIRECT_URI, true, "pt-BR");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("kc-form-login");
    }

    @Test
    void serviceAccount_HasOnlyUserManagementRoles() {
        String serviceAccountId = realm.admin().clients().get(cadastroClient().getId()).getServiceAccountUser().getId();
        String realmManagementId = realm.admin().clients().findByClientId("realm-management").getFirst().getId();

        List<RoleRepresentation> roles = realm.admin().users().get(serviceAccountId)
                .roles().clientLevel(realmManagementId).listAll();

        assertThat(roles).extracting(RoleRepresentation::getName)
                .containsExactlyInAnyOrder("view-users", "query-users", "manage-users");
    }

    @Test
    void serviceAccount_WithAdminApiScope_CanListUsersButNotManageTheRealm() throws Exception {
        String token = serviceAccountToken("kc-admin-api");
        String adminRealmUrl = keycloakUrls.getBase() + "/admin/realms/" + realm.getName();

        assertThat(OidcHttp.getWithBearer(httpClient, adminRealmUrl + "/users?max=1", token).status()).isEqualTo(200);
        assertThat(OidcHttp.getWithBearer(httpClient, adminRealmUrl + "/clients", token).status()).isEqualTo(403);
    }

    // A audiência da Admin API é opcional: tokens sem o scope (como os de login) não acessam a Admin API.
    @Test
    void serviceAccount_WithoutAdminApiScope_IsRejectedByAdminApi() throws Exception {
        String token = serviceAccountToken("openid");
        String usersUrl = keycloakUrls.getBase() + "/admin/realms/" + realm.getName() + "/users?max=1";

        assertThat(OidcHttp.getWithBearer(httpClient, usersUrl, token).status()).isEqualTo(401);
    }

    @Test
    void newUser_ReceivesUserRoleByDefault() {
        UserRepresentation user = UserBuilder.create("default.role.user").enabled(true).build();
        String userId;
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
            userId = realm.admin().users().search("default.role.user").getFirst().getId();
        }

        List<RoleRepresentation> effectiveRoles = realm.admin().users().get(userId).roles().realmLevel().listEffective();

        assertThat(effectiveRoles).extracting(RoleRepresentation::getName).contains("user").doesNotContain("admin");
    }

    @Test
    void realm_AllowsPasswordResetAndSendsMailThroughMailpit() {
        RealmRepresentation representation = realm.admin().toRepresentation();

        assertThat(representation.isResetPasswordAllowed()).isTrue();
        assertThat(representation.getSmtpServer())
                .containsEntry("host", "mailpit")
                .containsEntry("port", "1025")
                .containsEntry("from", "no-reply@workspace.local");
    }

    @Test
    void userProfile_FromProvisioningFile_IsAcceptedAndOnlyAdminsEdit() throws IOException {
        UPConfig config;
        try (InputStream file = getClass().getResourceAsStream("/workspace-user-profile.json")) {
            config = JsonSerialization.readValue(file, UPConfig.class);
        }

        realm.admin().users().userProfile().update(config);
        UPConfig applied = realm.admin().users().userProfile().getConfiguration();

        assertThat(applied.getAttributes()).extracting(UPAttribute::getName).contains(
                "mobilePhone", "postalCode", "street", "addressNumber", "complement", "district", "city",
                "cityIbgeCode", "state", "legacyUserId");
        assertThat(applied.getAttributes())
                .allSatisfy(attribute -> assertThat(attribute.getPermissions().getEdit()).containsExactly("admin"));
        assertThat(applied.getAttribute("legacyUserId").getPermissions().getView()).containsExactly("admin");
    }

    private ClientRepresentation cadastroClient() {
        return realm.admin().clients().findByClientId(CLIENT_ID).getFirst();
    }

    private String serviceAccountToken(String scope) throws IOException {
        String secret = realm.admin().clients().get(cadastroClient().getId()).getSecret().getValue();
        OidcHttp.Response response =
                OidcHttp.clientCredentialsGrant(httpClient, realm.getBaseUrl(), CLIENT_ID, secret, scope);
        assertThat(response.status()).isEqualTo(200);
        Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
