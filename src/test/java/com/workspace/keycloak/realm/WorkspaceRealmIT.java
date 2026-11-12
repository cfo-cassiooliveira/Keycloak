package com.workspace.keycloak.realm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.ws.rs.core.Response;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;

import com.workspace.keycloak.support.OidcHttp;
import com.workspace.keycloak.support.WorkspaceServerConfig;

/**
 * Valida o realm versionado em realm/realm-workspace.json (RN-04, RN-05, RN-06 e US-03 da Spec 001)
 * importado num Keycloak real com as extensões do projeto.
 */
@KeycloakIntegrationTest(config = WorkspaceServerConfig.class)
class WorkspaceRealmIT {

    private static final String STRONG_PASSWORD = "Workspace-Strong-1!";
    // Acima de quickLoginCheckMilliSeconds (1000 ms) do realm.
    private static final long QUICK_LOGIN_SPACING_MS = 1200;

    @InjectRealm(fromJson = "/realm-workspace.json")
    ManagedRealm realm;

    @InjectHttpClient
    HttpClient httpClient;

    @InjectKeycloakUrls
    KeycloakUrls keycloakUrls;

    @Test
    void realm_UsesOnlyWorkspaceAuditListenerAndStoresNoUserEvents() {
        RealmRepresentation representation = realm.admin().toRepresentation();

        assertThat(representation.getEventsListeners()).containsExactly("workspace-audit");
        assertThat(representation.isEventsEnabled()).isFalse();
        // O listener recebe as ações administrativas sem gravá-las no banco, onde ficaria o IP (R2).
        assertThat(representation.isAdminEventsEnabled()).isFalse();
        assertThat(representation.isAdminEventsDetailsEnabled()).isFalse();
        assertThat(representation.isBruteForceProtected()).isTrue();
        assertThat(representation.getLoginTheme()).isEqualTo("workspace");
        assertThat(representation.getDefaultLocale()).isEqualTo("pt-BR");
    }

    @Test
    void createUser_WhenPasswordIsWeak_IsRejectedByPasswordPolicy() {
        UserRepresentation user = UserBuilder.create("weak.password.user").build();
        user.setCredentials(List.of(password("weak")));

        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    void adminCli_WhenPasswordGrantIsUsed_IsRejected() throws Exception {
        createUser("ropc.user");

        OidcHttp.Response response = OidcHttp.passwordGrant(
                httpClient, realm.getBaseUrl(), "admin-cli", "ropc.user", STRONG_PASSWORD);

        assertThat(response.status()).isIn(400, 401);
        assertThat(response.body()).contains("unauthorized_client");
    }

    @Test
    void authorizationRequest_WithoutPkce_IsRejectedByClientPolicy() throws Exception {
        // Client novo, sem atributo de PKCE próprio: quem exige o PKCE é a client policy do realm.
        String redirectUri = "http://localhost:4200/callback";
        ClientRepresentation client = ClientBuilder.create("pkce-policy-test")
                .publicClient()
                .redirectUris(redirectUri)
                .build();
        try (Response created = realm.admin().clients().create(client)) {
            assertThat(created.getStatus()).isEqualTo(201);
        }

        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), "pkce-policy-test", redirectUri, false, "pt-BR");

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).startsWith(redirectUri).contains("error=invalid_request");
    }

    @Test
    void loginPage_WhenBrowserLanguageIsUnsupported_ShowsWorkspaceThemeInPortuguese() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), "account-console", accountRedirectUri("workspace"), true, "fr-FR");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("/login/workspace/vendor/bootstrap/bootstrap.min.css")
                .contains("/login/workspace/css/workspace.css?v=")
                .contains("Entrar no Workspace")
                .contains("Um acesso para todos os sistemas do Workspace.")
                .contains("class=\"btn btn-primary btn-lg w-100")
                .contains("class=\"form-control form-control-lg")
                .contains("autocomplete=\"current-password\"")
                .doesNotContain("tabindex=\"");
    }

    @Test
    void loginPage_ThemeResourcesAreServed() throws Exception {
        String body = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), "account-console", accountRedirectUri("workspace"), true, "pt-BR")
                .body();

        for (String resource : List.of("vendor/bootstrap/bootstrap.min.css", "css/workspace.css", "js/workspace.js",
                "fonts/manrope/manrope-latin-wght-normal.woff2", "img/logo.svg")) {
            Matcher href = Pattern.compile("(?:href|src)=\"([^\"]*/login/workspace/" + Pattern.quote(resource) + "(?:\\?v=[^\"]+)?)\"")
                    .matcher(body);
            assertThat(href.find()).as("link to %s", resource).isTrue();
            String url = href.group(1).startsWith("http") ? href.group(1) : keycloakUrls.getBase() + href.group(1);
            assertThat(OidcHttp.getStatus(httpClient, url)).as("status of %s", resource).isEqualTo(200);
        }
    }

    @Test
    void loginPage_WhenCredentialsAreInvalid_ShowsErrorNextToField() throws Exception {
        OidcHttp.Response response = OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), "account-console",
                accountRedirectUri("workspace"), "unknown.user", "wrong-password");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("id=\"input-error\" class=\"invalid-feedback d-block\"")
                .contains("aria-invalid=\"true\"")
                .contains("is-invalid");
    }

    @Test
    void loginPage_WhenBrowserLanguageIsEnglish_ShowsEnglishTitle() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), "account-console", accountRedirectUri("workspace"), true, "en");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("Sign in to Workspace");
    }

    @Test
    void masterLoginPage_KeepsDefaultTheme() throws Exception {
        OidcHttp.Response response = OidcHttp.authorizationRequest(
                httpClient, keycloakUrls.getMasterRealm(), "account-console", accountRedirectUri("master"), true,
                "pt-BR");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("workspace.css");
    }

    @Test
    void passwordGrant_AfterFiveSlowFailures_LocksUserButNotAfterFour() throws Exception {
        String clientId = createDirectGrantClient();
        createUser("brute.force.slow");

        // Espaçadas acima de quickLoginCheckMilliSeconds (1 s): mede só o failureFactor (5), não a regra de rapidez.
        slowFailures(clientId, "brute.force.slow", 4);
        assertThat(grant(clientId, "brute.force.slow", STRONG_PASSWORD).status())
                .as("4 falhas ainda abaixo do failureFactor").isEqualTo(200);

        slowFailures(clientId, "brute.force.slow", 5);
        OidcHttp.Response locked = grant(clientId, "brute.force.slow", STRONG_PASSWORD);

        assertThat(locked.status()).as("5 falhas atingem o failureFactor").isEqualTo(400);
        assertThat(locked.body()).contains("invalid_grant");
    }

    @Test
    void passwordGrant_AfterQuickRepeatedFailures_LocksUserTemporarily() throws Exception {
        String clientId = createDirectGrantClient();
        createUser("brute.force.quick");

        grant(clientId, "brute.force.quick", "wrong-password");
        grant(clientId, "brute.force.quick", "wrong-password");
        OidcHttp.Response response = grant(clientId, "brute.force.quick", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("invalid_grant");
    }

    @Test
    void loginPage_LanguageSwitcherHasTranslatedNameAndVisibleTextInAccessibleName() throws Exception {
        String body = OidcHttp.authorizationRequest(
                httpClient, realm.getBaseUrl(), "account-console", accountRedirectUri("workspace"), true, "pt-BR")
                .body();

        assertThat(body).contains("aria-label=\"Idiomas\"")
                // WCAG 2.5.3: sem aria-label nos links; a sigla visível no celular leva o nome completo junto.
                .doesNotContain("aria-label=\"Inglês", "aria-label=\"Português")
                .containsPattern("<span class=\"d-sm-none\">EN<span class=\"visually-hidden\"> [^<]+</span></span>");
    }

    @Test
    void errorPage_WhenLoginSessionCookieIsMissing_RendersInWorkspaceLayoutWithMessage() throws Exception {
        OidcHttp.Response response = OidcHttp.submitLoginFormWithoutCookies(httpClient, realm.getBaseUrl(),
                "account-console", accountRedirectUri("workspace"));

        // error.ftl do tema base mostra a mensagem no corpo (não em alerta), dentro do layout do Workspace.
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body())
                .contains("/login/workspace/css/workspace.css", "class=\"ws-title\"", "ws-brand")
                .contains("id=\"kc-error-message\"", "Cookie de reinício de login não encontrado")
                .contains("id=\"backToApplication\"");
    }

    @Test
    void updatePasswordPage_WhenPasswordIsTemporary_ShowsWarningAndBootstrapForm() throws Exception {
        UserRepresentation user = UserBuilder.create("temporary.password.user")
                .email("temporary.password.user@example.com").name("Temp", "User").emailVerified(true).enabled(true)
                .build();
        CredentialRepresentation temporary = password(STRONG_PASSWORD);
        temporary.setTemporary(true);
        user.setCredentials(List.of(temporary));
        try (Response created = realm.admin().users().create(user)) {
            assertThat(created.getStatus()).isEqualTo(201);
        }

        OidcHttp.Response response = OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), "account-console",
                accountRedirectUri("workspace"), "temporary.password.user", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("alert-warning\" role=\"status\"", "ws-icon ws-icon-warning")
                .contains("id=\"password-new\"", "form-control form-control-lg", "btn btn-primary");
    }

    @Test
    void otpPage_AfterPassword_ShowsAttemptedUsernameWithRestartLink() throws Exception {
        UserRepresentation user = UserBuilder.create("otp.user")
                .email("otp.user@example.com").name("Otp", "User").emailVerified(true).enabled(true).build();
        // Credencial TOTP no formato de importação do Keycloak: o login pede o código depois da senha.
        CredentialRepresentation otp = new CredentialRepresentation();
        otp.setType("otp");
        otp.setUserLabel("smoke");
        otp.setSecretData("{\"value\":\"JBSWY3DPEHPK3PXP\"}");
        otp.setCredentialData("{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}");
        user.setCredentials(List.of(password(STRONG_PASSWORD), otp));
        try (Response created = realm.admin().users().create(user)) {
            assertThat(created.getStatus()).isEqualTo(201);
        }

        OidcHttp.Response response = OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), "account-console",
                accountRedirectUri("workspace"), "otp.user", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("id=\"kc-attempted-username\"", "otp.user", "id=\"reset-login\"")
                .contains("id=\"otp\"", "/login/workspace/css/workspace.css");
    }

    private OidcHttp.Response grant(String clientId, String username, String password) throws Exception {
        return OidcHttp.passwordGrant(httpClient, realm.getBaseUrl(), clientId, username, password);
    }

    private void slowFailures(String clientId, String username, int count) throws Exception {
        for (int attempt = 0; attempt < count; attempt++) {
            Thread.sleep(QUICK_LOGIN_SPACING_MS);
            grant(clientId, username, "wrong-password");
        }
        Thread.sleep(QUICK_LOGIN_SPACING_MS);
    }

    private String accountRedirectUri(String realmName) {
        return keycloakUrls.getBase() + "/realms/" + realmName + "/account/";
    }

    // Client só do teste: o realm versionado não permite password grant em nenhum client.
    // Idempotente: vários testes do mesmo realm usam este client.
    private String createDirectGrantClient() {
        String clientId = "brute-force-test";
        if (!realm.admin().clients().findByClientId(clientId).isEmpty()) {
            return clientId;
        }
        ClientRepresentation client = ClientBuilder.create(clientId).publicClient().directAccessGrantsEnabled().build();
        try (Response response = realm.admin().clients().create(client)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
        return clientId;
    }

    private void createUser(String username) {
        UserRepresentation user = UserBuilder.create(username)
                .email(username + "@example.com")
                .name("Test", "User")
                .emailVerified(true)
                .enabled(true)
                .build();
        user.setCredentials(List.of(password(STRONG_PASSWORD)));
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
    }

    private static CredentialRepresentation password(String value) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(value);
        credential.setTemporary(false);
        return credential;
    }
}
