package com.workspace.keycloak.realm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.ws.rs.core.Response;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.authenticators.broker.IdpDetectExistingBrokerUserAuthenticator;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;
import org.keycloak.util.JsonSerialization;

import com.workspace.keycloak.support.FakeGraylog;
import com.workspace.keycloak.support.LegacyServerConfig;

/**
 * Login social com Google (ADR-0009) sobre os fluxos versionados no realm. O Google é simulado por outro realm do mesmo
 * Keycloak, ligado com o mesmo JSON do provisioner (só o provider vira "oidc", com os endpoints do realm simulado).
 */
@KeycloakIntegrationTest(config = LegacyServerConfig.class)
class GoogleLoginIT {

    private static final String PASSWORD = "Workspace-Strong-1!";
    private static final String CLIENT_ID = "cadastro";
    private static final String REDIRECT_URI = "http://localhost:4300/bff/signin-oidc";
    private static final String SIM_CLIENT_ID = "workspace-broker";
    private static final String SIM_CLIENT_SECRET = "test-only-broker-secret";
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String LOGIN_FORM = "kc-form-login";
    private static final String OTP_FORM = "kc-otp-login-form";
    private static final String TOTP_SETUP_FORM = "kc-totp-settings-form";
    private static final int MAX_HOPS = 12;
    private static final Pattern GOOGLE_BUTTON = Pattern.compile("id=\"social-google\"[^>]*href=\"([^\"]+)\"");

    private static final Pattern PREFILLED_USERNAME =
            Pattern.compile("<input id=\"username\" name=\"username\" type=\"text\" value=\"([^\"]+)\"");

    @InjectRealm(fromJson = "/realm-workspace.json")
    ManagedRealm realm;

    @InjectRealm(ref = "google-sim")
    ManagedRealm google;

    @InjectHttpClient
    HttpClient httpClient;

    @InjectKeycloakUrls
    KeycloakUrls keycloakUrls;

    private final Map<String, String> lastTotp = new HashMap<>();

    private final FakeGraylog graylog = FakeGraylog.instance();

    @BeforeEach
    void setUpSimulatedGoogle() throws IOException {
        applyUserProfile();
        applyLocalization();
        if (google.admin().clients().findByClientId(SIM_CLIENT_ID).isEmpty()) {
            RealmRepresentation representation = google.admin().toRepresentation();
            representation.setLoginTheme("workspace");
            google.admin().update(representation);
            ClientRepresentation client = new ClientRepresentation();
            client.setClientId(SIM_CLIENT_ID);
            client.setSecret(SIM_CLIENT_SECRET);
            client.setPublicClient(false);
            client.setRedirectUris(List.of(realm.getBaseUrl() + "/broker/google/endpoint"));
            try (Response response = google.admin().clients().create(client)) {
                assertThat(response.getStatus()).isEqualTo(201);
            }
        }
        IdentityProviderRepresentation provider = versionedProvider(SIM_CLIENT_ID, SIM_CLIENT_SECRET);
        provider.setProviderId("oidc");
        Map<String, String> config = provider.getConfig();
        String oidc = google.getBaseUrl() + "/protocol/openid-connect";
        config.put("issuer", google.getBaseUrl());
        config.put("authorizationUrl", oidc + "/auth");
        config.put("tokenUrl", oidc + "/token");
        config.put("jwksUrl", oidc + "/certs");
        config.put("useJwksUrl", "true");
        config.put("validateSignature", "true");
        config.put("clientAuthMethod", "client_secret_post");
        replaceProvider(provider);
    }

    @Test
    void versionedFlows_NeverCreateUsersNorLinkWithoutThePassword() {
        List<AuthenticationExecutionInfoRepresentation> first =
                realm.admin().flows().getExecutions("workspace-first-broker-login");
        List<AuthenticationExecutionInfoRepresentation> post =
                realm.admin().flows().getExecutions("workspace-post-broker-login");

        assertThat(first).extracting(AuthenticationExecutionInfoRepresentation::getProviderId)
                .containsExactly("idp-detect-existing-broker-user", "idp-username-password-form");
        assertThat(first).allSatisfy(execution -> assertThat(execution.getRequirement()).isEqualTo("REQUIRED"));
        // O "allow access" no fim faz o fluxo ter sucesso para quem não cai em nenhum subflow de OTP.
        assertThat(post).filteredOn(execution -> execution.getLevel() == 0)
                .extracting(AuthenticationExecutionInfoRepresentation::getRequirement)
                .containsExactly("CONDITIONAL", "CONDITIONAL", "CONDITIONAL", "CONDITIONAL", "REQUIRED");
        assertThat(post.getLast().getProviderId()).isEqualTo("allow-access-authenticator");
        assertThat(post).filteredOn(execution -> "auth-otp-form".equals(execution.getProviderId())).hasSize(4);
        assertThat(post).extracting(AuthenticationExecutionInfoRepresentation::getProviderId)
                .doesNotContain("conditional-level-of-authentication");
    }

    // O realm versionado declara as required actions: nenhuma padrão pode sumir e só o idp_link muda.
    @Test
    void versionedRequiredActions_KeepTheDefaultsAndOnlyDisableAccountLinking() {
        Map<String, Boolean> defaults = enabledByAlias(google);
        Map<String, Boolean> workspace = enabledByAlias(realm);

        assertThat(defaults).containsEntry("idp_link", true);
        defaults.put("idp_link", false);
        assertThat(workspace).isEqualTo(defaults);
    }

    // Sessão aberta não prova a posse da conta: nenhum caminho do Keycloak pode vincular o Google sem a senha.
    @Test
    void openSession_CannotLinkGoogleWithoutThePassword() throws Exception {
        String userId = createAccount("open.session", true, true);
        Browser browser = new Browser();
        Page login = browser.submit(browser.openLogin(null), LOGIN_FORM,
                Map.of("username", "open.session", "password", PASSWORD));
        assertThat(login.code()).isTrue();

        // 1) Ação iniciada pela aplicação, no Account Console: o client que tem manage-account no escopo e, por isso,
        // o que chegaria à tela de vínculo.
        Page action = accountConsoleAuthorization(browser, "idp_link:google");
        assertThat(action.location()).startsWith(realm.getBaseUrl() + "/account/")
                .contains("kc_action_status=error").contains("code=");

        // 2) Endpoint legado de vínculo do broker, com o hash correto da sessão.
        String nonce = "link-nonce";
        String hash = linkHash(nonce, queryParam(login.location(), "session_state"), CLIENT_ID);
        Page legacy = browser.sendOnce(new HttpGet(new URIBuilder(realm.getBaseUrl() + "/broker/google/link")
                .addParameter("client_id", CLIENT_ID)
                .addParameter("redirect_uri", REDIRECT_URI)
                .addParameter("nonce", nonce)
                .addParameter("hash", hash).build()));
        assertThat(legacy.status()).isEqualTo(400);

        // 3) Account API (linked-accounts), com o token do próprio Account Console.
        HttpGet accountApi = new HttpGet(new URIBuilder(realm.getBaseUrl() + "/account/linked-accounts/google")
                .addParameter("redirectUri", realm.getBaseUrl() + "/account/").build());
        accountApi.setHeader("Authorization", "Bearer " + accountConsoleToken(browser));
        accountApi.setHeader("Accept", "application/json");
        Page api = browser.sendOnce(accountApi);
        assertThat(api.status()).as(api.body()).isEqualTo(404);
        assertThat(api.body()).contains("account linking is disabled");

        assertThat(links(userId)).isEmpty();
    }

    @Test
    void compose_SilencesTheAuthenticatorThatWouldLogTheEmail() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertThat(compose).contains(IdpDetectExistingBrokerUserAuthenticator.class.getName() + ":off");
    }

    // O JSON do provisioner aplicado como está (provider "google" nativo), com credenciais falsas.
    @Test
    void googleProvider_RedirectsToGoogleWithMinimalScopesPkceAndNoStoredToken() throws Exception {
        replaceProvider(versionedProvider("fake-client.apps.googleusercontent.com", "fake-secret-value"));
        Browser browser = new Browser();
        Page login = browser.openLogin(null);

        Page redirect = browser.sendOnce(new HttpGet(googleButton(login)));

        assertThat(redirect.location()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        Map<String, String> query = new URIBuilder(redirect.location()).getQueryParams().stream()
                .collect(Collectors.toMap(pair -> pair.getName(), pair -> pair.getValue()));
        assertThat(query).containsEntry("client_id", "fake-client.apps.googleusercontent.com")
                .containsEntry("redirect_uri", realm.getBaseUrl() + "/broker/google/endpoint")
                .containsEntry("scope", "openid email")
                .containsEntry("code_challenge_method", "S256")
                .doesNotContainKey("access_type");
        IdentityProviderRepresentation stored = realm.admin().identityProviders().get("google").toRepresentation();
        assertThat(stored.isStoreToken()).isFalse();
        assertThat(stored.isTrustEmail()).isFalse();
        assertThat(stored.isLinkOnly()).isFalse();
        assertThat(stored.isAddReadTokenRoleOnCreate()).isFalse();
        assertThat(stored.getConfig()).containsEntry("syncMode", "IMPORT");
        assertThat(realm.admin().identityProviders().get("google").getMappers()).isEmpty();
    }

    // Cobre também o usuário que só existe no Legado: sem conta no Keycloak não entra nem é criado.
    @Test
    void googleLogin_WhenThereIsNoAccount_IsDeniedAndCreatesNothing() throws Exception {
        createGoogleUser("nobody.here", true);
        int usersBefore = realm.admin().users().count();

        Page result = new Browser().signInWithGoogle("nobody.here", null);

        assertThat(result.code()).isFalse();
        assertThat(result.status()).isEqualTo(401);
        assertThat(result.body()).contains("Não há uma conta do Workspace para este acesso.")
                .doesNotContain(email("nobody.here"));
        assertThat(result.body()).doesNotContain("id=\"" + LOGIN_FORM + "\"");
        assertThat(realm.admin().users().count()).isEqualTo(usersBefore);
        assertThat(realm.admin().users().searchByEmail(email("nobody.here"), true)).isEmpty();
    }

    @Test
    void googleLogin_WhenGoogleDidNotVerifyTheEmail_IsDeniedWithoutLinking() throws Exception {
        String userId = createAccount("unverified.mail", true, false);
        createGoogleUser("unverified.mail", false);

        Page result = new Browser().signInWithGoogle("unverified.mail", null);

        assertThat(result.code()).isFalse();
        assertThat(result.body()).doesNotContain("id=\"" + LOGIN_FORM + "\"")
                .contains("O Google não confirmou o seu e-mail");
        assertThat(links(userId)).isEmpty();
    }

    @Test
    void firstGoogleLogin_LinksOnlyAfterTheAccountPasswordAndKeepsTheProfile() throws Exception {
        String userId = createAccount("link.me", true, false, Map.of("importLineId", List.of("line-1")));
        createGoogleUser("link.me", true);
        Browser browser = new Browser();

        Page confirm = browser.signInWithGoogle("link.me", null);
        assertThat(confirm.code()).as("code sem provar a posse da conta").isFalse();
        assertThat(links(userId)).isEmpty();
        Page wrong = browser.submit(confirm, LOGIN_FORM, Map.of("password", "Wrong-Password-1!"));
        assertThat(wrong.code()).isFalse();
        assertThat(links(userId)).isEmpty();
        Page done = browser.submit(wrong, LOGIN_FORM, Map.of("password", PASSWORD));

        assertThat(done.code()).as(done.body()).isTrue();
        assertThat(links(userId)).containsExactly("google");
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> graylog.received().stream()
                .anyMatch(gelf -> gelf.contains("FEDERATED_IDENTITY_LINK") && gelf.contains(userId)));
        assertThat(graylog.received()).noneMatch(gelf -> gelf.contains(email("link.me")));
        Map<?, ?> claims = accessClaims(done);
        assertThat(claims.get("acr")).isEqualTo("password");
        assertThat(claims.get("account_origin")).isEqualTo("imported");
        UserRepresentation user = realm.admin().users().get(userId).toRepresentation();
        // trustEmail=false e sem mappers: o Google não confirma o e-mail nem altera atributos da conta.
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getUsername()).isEqualTo("link.me");
        assertThat(user.getFirstName()).isEqualTo("Test");
        assertThat(user.getAttributes()).containsEntry("importLineId", List.of("line-1"));

        Page again = new Browser().signInWithGoogle("link.me", null);
        assertThat(again.code()).as("conta já vinculada e sem OTP entra direto").isTrue();
        assertThat(accessClaims(again).get("acr")).isEqualTo("password");
    }

    // A tela vem com o login da conta de mesmo e-mail, mas o campo é editável: em qualquer caso o vínculo exige a
    // senha da conta escolhida.
    @Test
    void firstGoogleLogin_WhenAnotherAccountIsTypedWithoutItsPassword_LinksNothing() throws Exception {
        String sameEmail = createAccount("same.email", true, true);
        String other = createAccount("other.account", false, true);
        createGoogleUser("same.email", true);
        Browser browser = new Browser();

        Page confirm = browser.signInWithGoogle("same.email", null);
        Page result = browser.submit(confirm, LOGIN_FORM,
                Map.of("username", "other.account", "password", PASSWORD));

        assertThat(confirm.body()).contains("value=\"same.email\"");
        assertThat(result.code()).isFalse();
        assertThat(links(sameEmail)).isEmpty();
        assertThat(links(other)).isEmpty();
    }

    // Conta criada pelo reset ou pela importação e ainda sem senha: o Google não substitui a migração nem a ativação.
    @Test
    void firstGoogleLogin_WhenAccountHasNoPassword_CannotBeLinked() throws Exception {
        String userId = createAccount("shell.user", false, true, Map.of("legacyUserId",
                List.of("7c1d4a52-2222-4333-8444-a55566667777")));
        createGoogleUser("shell.user", true);
        Browser browser = new Browser();

        Page confirm = browser.signInWithGoogle("shell.user", null);
        Page result = browser.submit(confirm, LOGIN_FORM, Map.of("password", PASSWORD));

        assertThat(result.code()).isFalse();
        assertThat(links(userId)).isEmpty();
        assertThat(realm.admin().users().get(userId).credentials()).isEmpty();
    }

    @Test
    void googleLogin_WhenUserHasOtp_AlwaysAsksForItAndNeverIssuesOtpAcr() throws Exception {
        createAccount("with.otp", true, true, Map.of(), totp());
        createGoogleUser("with.otp", true);
        Browser browser = new Browser();

        Page otp = browser.submit(browser.signInWithGoogle("with.otp", null), LOGIN_FORM, Map.of("password", PASSWORD));
        assertThat(otp.code()).isFalse();
        Page first = browser.submitOtp(otp, "with.otp");
        Page again = new Browser().signInWithGoogle("with.otp", null);

        assertThat(first.code()).isTrue();
        assertThat(accessClaims(first).get("acr")).isEqualTo("password");
        assertThat(again.code()).as("conta vinculada entrou sem OTP").isFalse();
        assertThat(again.body()).contains("id=\"" + OTP_FORM + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "log-investigator", "user-importer"})
    void googleLogin_WhenPrivilegedUserHasNoOtp_MustConfigureItBeforeAnyCode(String role) throws Exception {
        String username = "no.otp." + role;
        grant(createAccount(username, true, true), role);
        createGoogleUser(username, true);
        Browser browser = new Browser();

        Page result = browser.submit(browser.signInWithGoogle(username, null), LOGIN_FORM,
                Map.of("password", PASSWORD));

        assertThat(result.code()).isFalse();
        assertThat(result.body()).contains("id=\"" + TOTP_SETUP_FORM + "\"");
    }

    @Test
    void googleLogin_WhenUserHasEveryPrivilegedRole_AsksOtpOnce() throws Exception {
        String userId = createAccount("all.roles", true, true, Map.of(), totp());
        grant(userId, "admin");
        grant(userId, "log-investigator");
        grant(userId, "user-importer");
        createGoogleUser("all.roles", true);
        Browser browser = new Browser();

        Page otp = browser.submit(browser.signInWithGoogle("all.roles", null), LOGIN_FORM,
                Map.of("password", PASSWORD));
        Page done = browser.submitOtp(otp, "all.roles");

        assertThat(done.code()).as("um segundo OTP foi pedido").isTrue();
    }

    // Contrato com o Cadastro: pedir acr_values=otp e entrar pelo Google não produz acr=otp; o step-up segue exigido.
    @Test
    void googleLogin_WithOtpAcrRequest_DoesNotIssueOtpAcrAndTheStepUpStillAsksForOtp() throws Exception {
        grant(createAccount("step.up", true, true, Map.of(), totp()), "log-investigator");
        createGoogleUser("step.up", true);
        Browser browser = new Browser();
        Page otp = browser.submit(browser.signInWithGoogle("step.up", "otp"), LOGIN_FORM, Map.of("password", PASSWORD));

        Page viaGoogle = browser.submitOtp(otp, "step.up");
        assertThat(accessClaims(viaGoogle).get("acr")).isEqualTo("password");

        Page stepUp = browser.openLogin("otp");
        assertThat(stepUp.code()).as("SSO devolveu code sem o OTP do step-up").isFalse();
        if (stepUp.body().contains("id=\"" + LOGIN_FORM + "\"")) {
            stepUp = browser.submit(stepUp, LOGIN_FORM, Map.of("password", PASSWORD));
        }
        Page done = browser.submitOtp(stepUp, "step.up");

        assertThat(accessClaims(done).get("acr")).isEqualTo("otp");
    }

    private IdentityProviderRepresentation versionedProvider(String clientId, String secret) throws IOException {
        try (InputStream file = getClass().getResourceAsStream("/workspace-google-idp.json")) {
            String json = new String(file.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__GOOGLE_CLIENT_ID__", clientId)
                    .replace("__GOOGLE_CLIENT_SECRET__", secret);
            return JsonSerialization.readValue(json, IdentityProviderRepresentation.class);
        }
    }

    private void replaceProvider(IdentityProviderRepresentation provider) {
        if (realm.admin().identityProviders().findAll().stream().anyMatch(idp -> "google".equals(idp.getAlias()))) {
            realm.admin().identityProviders().get("google").remove();
        }
        try (Response response = realm.admin().identityProviders().create(provider)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
    }

    private void applyUserProfile() throws IOException {
        try (InputStream file = getClass().getResourceAsStream("/workspace-user-profile.json")) {
            realm.admin().users().userProfile().update(JsonSerialization.readValue(file, UPConfig.class));
        }
    }

    private static Map<String, Boolean> enabledByAlias(ManagedRealm target) {
        return target.admin().flows().getRequiredActions().stream().collect(Collectors.toMap(
                RequiredActionProviderRepresentation::getAlias, RequiredActionProviderRepresentation::isEnabled));
    }

    private static String queryParam(String url, String name) throws URISyntaxException {
        return new URIBuilder(url).getQueryParams().stream()
                .filter(param -> param.getName().equals(name)).findFirst().orElseThrow().getValue();
    }

    // Formato exigido pelo endpoint legado: SHA-256 de nonce + sessão + client + provedor.
    private static String linkHash(String nonce, String sessionState, String clientId) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((nonce + sessionState + clientId + "google").getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private Page accountConsoleAuthorization(Browser browser, String kcAction) throws Exception {
        URIBuilder uri = new URIBuilder(realm.getBaseUrl() + "/protocol/openid-connect/auth")
                .addParameter("client_id", "account-console")
                .addParameter("response_type", "code")
                .addParameter("scope", "openid")
                .addParameter("redirect_uri", realm.getBaseUrl() + "/account/")
                .addParameter("code_challenge", CODE_CHALLENGE)
                .addParameter("code_challenge_method", "S256");
        if (kcAction != null) {
            uri.addParameter("kc_action", kcAction);
        }
        return browser.sendOnce(new HttpGet(uri.build()));
    }

    // Com o SSO já aberto, o Account Console recebe o code direto; o client é público com PKCE.
    private String accountConsoleToken(Browser browser) throws Exception {
        String redirectUri = realm.getBaseUrl() + "/account/";
        Page code = accountConsoleAuthorization(browser, null);
        HttpPost post = new HttpPost(realm.getBaseUrl() + "/protocol/openid-connect/token");
        post.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair("grant_type", "authorization_code"),
                new BasicNameValuePair("client_id", "account-console"),
                new BasicNameValuePair("code", queryParam(code.location(), "code")),
                new BasicNameValuePair("redirect_uri", redirectUri),
                new BasicNameValuePair("code_verifier", CODE_VERIFIER)), StandardCharsets.UTF_8));
        Page response = new Browser().sendOnce(post);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return (String) JsonSerialization.readValue(response.body(), Map.class).get("access_token");
    }

    // Os mesmos textos que o provisioner aplica no realm.
    private void applyLocalization() throws IOException {
        try (InputStream file = getClass().getResourceAsStream("/workspace-localization-pt-BR.json")) {
            Map<?, ?> texts = JsonSerialization.readValue(file, Map.class);
            texts.forEach((key, value) -> realm.admin().localization()
                    .saveRealmLocalizationText("pt-BR", (String) key, (String) value));
        }
    }

    private static String email(String username) {
        return username + "@example.com";
    }

    private String createAccount(String username, boolean withPassword, boolean emailVerified) {
        return createAccount(username, withPassword, emailVerified, Map.of());
    }

    private String createAccount(String username, boolean withPassword, boolean emailVerified,
            Map<String, List<String>> attributes, CredentialRepresentation... extraCredentials) {
        UserRepresentation user = UserBuilder.create(username)
                .email(email(username)).name("Test", "User").emailVerified(emailVerified).enabled(true).build();
        user.setAttributes(new HashMap<>(attributes));
        List<CredentialRepresentation> credentials = new ArrayList<>(Arrays.asList(extraCredentials));
        if (withPassword) {
            credentials.add(password());
        }
        user.setCredentials(credentials);
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
        return realm.admin().users().search(username, true).getFirst().getId();
    }

    private void createGoogleUser(String username, boolean emailVerified) {
        UserRepresentation user = UserBuilder.create(username)
                .email(email(username)).name("Google", "Account").emailVerified(emailVerified).enabled(true).build();
        user.setCredentials(List.of(password()));
        try (Response response = google.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
    }

    private static CredentialRepresentation password() {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        return credential;
    }

    private static CredentialRepresentation totp() {
        CredentialRepresentation otp = new CredentialRepresentation();
        otp.setType("otp");
        otp.setUserLabel("test");
        otp.setSecretData("{\"value\":\"" + TOTP_SECRET + "\"}");
        otp.setCredentialData("{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}");
        return otp;
    }

    private void grant(String userId, String role) {
        realm.admin().users().get(userId).roles().realmLevel()
                .add(List.of(realm.admin().roles().get(role).toRepresentation()));
    }

    private List<String> links(String userId) {
        return realm.admin().users().get(userId).getFederatedIdentity().stream()
                .map(identity -> identity.getIdentityProvider()).toList();
    }

    private String googleButton(Page loginPage) {
        Matcher button = GOOGLE_BUTTON.matcher(loginPage.body());
        assertThat(button.find()).as("botão do Google na tela de login").isTrue();
        return URI.create(keycloakUrls.getBase().toString()).resolve(button.group(1).replace("&amp;", "&")).toString();
    }

    // Troca o code pelo access token e devolve as claims.
    private Map<?, ?> accessClaims(Page done) throws Exception {
        assertThat(done.code()).as(done.body()).isTrue();
        String code = new URIBuilder(done.location()).getQueryParams().stream()
                .filter(param -> param.getName().equals("code")).findFirst().orElseThrow().getValue();
        String clientId = realm.admin().clients().findByClientId(CLIENT_ID).getFirst().getId();
        HttpPost post = new HttpPost(realm.getBaseUrl() + "/protocol/openid-connect/token");
        post.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair("grant_type", "authorization_code"),
                new BasicNameValuePair("client_id", CLIENT_ID),
                new BasicNameValuePair("client_secret",
                        realm.admin().clients().get(clientId).getSecret().getValue()),
                new BasicNameValuePair("code", code),
                new BasicNameValuePair("redirect_uri", REDIRECT_URI),
                new BasicNameValuePair("code_verifier", CODE_VERIFIER)), StandardCharsets.UTF_8));
        Page response = new Browser().sendOnce(post);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String token = (String) JsonSerialization.readValue(response.body(), Map.class).get("access_token");
        return JsonSerialization.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
    }

    private record Page(int status, String body, String location) {

        // Fim do fluxo: redirecionamento ao client com o authorization code.
        boolean code() {
            return location != null && location.startsWith(REDIRECT_URI) && location.contains("code=");
        }
    }

    /**
     * Navegador mínimo: um pote de cookies por realm (os dois realms usam os mesmos nomes no mesmo host; o Keycloak os
     * marca "Secure" e o HttpClient não os reenvia por http).
     */
    private final class Browser {

        private final Map<String, Map<String, String>> jars = new HashMap<>();

        Page openLogin(String acrValues) throws IOException, URISyntaxException {
            URIBuilder uri = new URIBuilder(realm.getBaseUrl() + "/protocol/openid-connect/auth")
                    .addParameter("client_id", CLIENT_ID)
                    .addParameter("response_type", "code")
                    .addParameter("scope", "openid")
                    .addParameter("redirect_uri", REDIRECT_URI)
                    .addParameter("code_challenge", CODE_CHALLENGE)
                    .addParameter("code_challenge_method", "S256");
            if (acrValues != null) {
                uri.addParameter("acr_values", acrValues);
            }
            return follow(sendOnce(new HttpGet(uri.build())));
        }

        // Tela de login do Workspace -> botão do Google -> senha no "Google" -> volta pelo broker.
        Page signInWithGoogle(String googleUsername, String acrValues) throws IOException, URISyntaxException {
            Page googleLogin = follow(sendOnce(new HttpGet(googleButton(openLogin(acrValues)))));
            return submit(googleLogin, LOGIN_FORM, Map.of("username", googleUsername, "password", PASSWORD));
        }

        Page submit(Page page, String formId, Map<String, String> fields) throws IOException {
            Matcher form = Pattern.compile("<form id=\"" + formId + "\"[^>]*action=\"([^\"]+)\"").matcher(page.body());
            assertThat(form.find()).as(formId + " na página: " + page.body()).isTrue();
            HttpPost post = new HttpPost(form.group(1).replace("&amp;", "&"));
            // Como o navegador: o login pré-preenchido pela tela vai junto quando o teste não digita outro.
            Map<String, String> sent = new LinkedHashMap<>(fields);
            Matcher prefilled = PREFILLED_USERNAME.matcher(page.body());
            if (LOGIN_FORM.equals(formId) && prefilled.find()) {
                sent.putIfAbsent("username", prefilled.group(1));
            }
            post.setEntity(new UrlEncodedFormEntity(sent.entrySet().stream()
                    .map(field -> new BasicNameValuePair(field.getKey(), field.getValue())).toList(),
                    StandardCharsets.UTF_8));
            return follow(sendOnce(post));
        }

        // O Keycloak não aceita o mesmo código duas vezes: espera a próxima janela quando preciso.
        Page submitOtp(Page page, String username) throws IOException {
            TimeBasedOTP generator = new TimeBasedOTP("HmacSHA1", 6, 30, 1);
            Awaitility.await().atMost(Duration.ofSeconds(35)).pollInterval(Duration.ofMillis(500))
                    .until(() -> !generator.generateTOTP(TOTP_SECRET).equals(lastTotp.get(username)));
            lastTotp.put(username, generator.generateTOTP(TOTP_SECRET));
            return submit(page, OTP_FORM, Map.of("otp", lastTotp.get(username)));
        }

        private Page follow(Page page) throws IOException {
            Page current = page;
            for (int hop = 0; hop < MAX_HOPS && current.status() >= 300 && current.status() < 400
                    && !current.location().startsWith(REDIRECT_URI); hop++) {
                current = sendOnce(new HttpGet(URI.create(keycloakUrls.getBase().toString())
                        .resolve(current.location())));
            }
            return current;
        }

        Page sendOnce(HttpRequestBase request) throws IOException {
            request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            request.setHeader("Accept-Language", "pt-BR");
            String realmName = request.getURI().getPath().replaceFirst("^.*/realms/([^/]+).*$", "$1");
            Map<String, String> cookies = jars.computeIfAbsent(realmName, name -> new LinkedHashMap<>());
            if (!cookies.isEmpty()) {
                request.setHeader("Cookie", cookies.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("; ")));
            }
            HttpResponse response = httpClient.execute(request, HttpClientContext.create());
            for (Header header : response.getHeaders("Set-Cookie")) {
                String[] pair = header.getValue().split(";", 2)[0].split("=", 2);
                cookies.put(pair[0], pair.length > 1 ? pair[1] : "");
            }
            String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
            Header location = response.getFirstHeader("Location");
            return new Page(response.getStatusLine().getStatusCode(), body,
                    location == null ? null : location.getValue());
        }
    }
}
