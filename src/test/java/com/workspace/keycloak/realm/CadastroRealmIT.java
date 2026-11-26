package com.workspace.keycloak.realm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.ws.rs.core.Response;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.ClientScopeEvaluateResource;
import org.keycloak.admin.client.resource.ProtocolMappersResource;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
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

import com.workspace.keycloak.support.BrowserFlow;
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
    private static final Pattern AUTHORIZATION_CODE = Pattern.compile("[?&#]code=([^&]+)");
    // Par do code challenge fixo do BrowserFlow (exemplo da RFC 7636).
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String PASSWORD = "Workspace-Strong-1!";
    private static final String ORIGIN_MAPPER = "workspace-account-origin-mapper";
    private static final String ORIGIN_CLAIM = "account_origin";

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

    // Minimização (LGPD): tokens do Cadastro só com id, login, roles e origem da conta; sem profile/email.
    @Test
    void cadastroClient_TokensCarryOnlyLoginRolesAndAccountOrigin() {
        ClientRepresentation client = cadastroClient();

        assertThat(client.getDefaultClientScopes()).containsExactlyInAnyOrder("basic", "acr", "roles");
        assertThat(client.getOptionalClientScopes()).containsExactly("kc-admin-api");
        assertThat(client.getProtocolMappers())
                .extracting(ProtocolMapperRepresentation::getName, ProtocolMapperRepresentation::getProtocolMapper,
                        mapper -> mapper.getConfig().get("claim.name"))
                .containsExactlyInAnyOrder(
                        tuple("login", "oidc-usermodel-property-mapper", "preferred_username"),
                        tuple("account-origin", ORIGIN_MAPPER, ORIGIN_CLAIM));
    }

    // P3 da spec 002 do rabbitmq: o claim é só do client do Cadastro.
    @Test
    void accountOriginMapper_ExistsOnlyOnTheCadastroClient() {
        assertThat(realm.admin().clients().findAll())
                .filteredOn(client -> hasOriginMapper(client.getProtocolMappers()))
                .extracting(ClientRepresentation::getClientId)
                .containsExactly(CLIENT_ID);
        assertThat(realm.admin().clientScopes().findAll())
                .noneMatch(scope -> hasOriginMapper(scope.getProtocolMappers()));
    }

    @ParameterizedTest
    @CsvSource({
            "origin.imported, importLineId, 0b9f2c1e-1111-4222-8333-944455556666, imported",
            "origin.migrated, legacyUserId, 7c1d4a52-2222-4333-8444-a55566667777, migrated",
    })
    void cadastroTokens_CarryOnlyTheAccountOriginAndNeverTheAttributeValue(String username, String attribute,
            String value, String origin) throws IOException {
        applyUserProfile();
        String userId = createUserWithAttributes(username, Map.of(attribute, List.of(value)));
        ClientScopeEvaluateResource evaluate =
                realm.admin().clients().get(cadastroClient().getId()).clientScopesEvaluate();

        AccessToken accessToken = evaluate.generateAccessToken("openid", userId, null);
        IDToken idToken = evaluate.generateExampleIdToken("openid", userId, null);
        Map<String, Object> userInfo = evaluate.generateExampleUserinfo("openid", userId);

        assertThat(accessToken.getOtherClaims()).containsEntry(ORIGIN_CLAIM, origin);
        assertThat(idToken.getOtherClaims()).containsEntry(ORIGIN_CLAIM, origin);
        assertThat(userInfo).containsEntry(ORIGIN_CLAIM, origin);
        assertThat(JsonSerialization.writeValueAsString(List.of(accessToken, idToken, userInfo))).doesNotContain(value);
    }

    // Autocadastro e contas criadas pelo administrador: nenhum dos dois atributos, nenhum claim.
    @Test
    void cadastroToken_WhenAccountIsNeitherImportedNorMigrated_HasNoOriginClaim() throws IOException {
        applyUserProfile();
        String userId = createUserWithAttributes("origin.selfservice", Map.of());

        AccessToken token = realm.admin().clients().get(cadastroClient().getId()).clientScopesEvaluate()
                .generateAccessToken("openid", userId, null);

        assertThat(token.getOtherClaims()).doesNotContainKey(ORIGIN_CLAIM);
    }

    @Test
    void grafanaToken_ForAnImportedAccount_HasNoOriginClaim() throws IOException {
        applyUserProfile();
        String userId = createUserWithAttributes("origin.grafana", Map.of("importLineId", List.of("line-grafana")));
        String grafanaId = realm.admin().clients().findByClientId("grafana").getFirst().getId();

        AccessToken token = realm.admin().clients().get(grafanaId).clientScopesEvaluate()
                .generateAccessToken("openid", userId, null);

        assertThat(token.getOtherClaims()).doesNotContainKey(ORIGIN_CLAIM);
    }

    // Introspecção com sessão real no client cadastro: a resposta também traz a origem. O Keycloak só responde a quem
    // está na audiência do token, o que o realm versionado não faz com o cadastro; o teste a adiciona e depois remove.
    @Test
    void introspection_OfACadastroTokenFromAnImportedAccount_CarriesTheOrigin() throws Exception {
        applyUserProfile();
        String userId = createUserWithAttributes("origin.introspect", Map.of("importLineId", List.of("line-intro")));
        setPassword(userId);
        ProtocolMappersResource mappers = realm.admin().clients().get(cadastroClient().getId()).getProtocolMappers();
        String audienceMapperId;
        try (Response response = mappers.createMapper(selfAudienceMapper())) {
            audienceMapperId = CreatedResponseUtil.getCreatedId(response);
        }
        try {
            assertThat(introspectOwnToken("origin.introspect"))
                    .contains("\"active\":true", "\"account_origin\":\"imported\"")
                    .doesNotContain("line-intro");
        } finally {
            mappers.delete(audienceMapperId);
        }
    }

    private static ProtocolMapperRepresentation selfAudienceMapper() {
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("test-self-audience");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-audience-mapper");
        mapper.setConfig(Map.of("included.client.audience", CLIENT_ID, "access.token.claim", "true",
                "introspection.token.claim", "true"));
        return mapper;
    }

    private String introspectOwnToken(String username) throws IOException {
        String secret = realm.admin().clients().get(cadastroClient().getId()).getSecret().getValue();
        OidcHttp.Response login = OidcHttp.submitLoginForm(
                httpClient, realm.getBaseUrl(), CLIENT_ID, REDIRECT_URI, username, PASSWORD);
        String token = exchangeCode(login.location(), REDIRECT_URI, new BasicNameValuePair("client_id", CLIENT_ID),
                new BasicNameValuePair("client_secret", secret));

        HttpPost post = new HttpPost(realm.getBaseUrl() + "/protocol/openid-connect/token/introspect");
        post.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair("client_id", CLIENT_ID),
                new BasicNameValuePair("client_secret", secret),
                new BasicNameValuePair("token", token)), StandardCharsets.UTF_8));
        return EntityUtils.toString(httpClient.execute(post).getEntity());
    }

    // Segurança do claim: pela Account API, com o token do próprio usuário, não dá para ver, criar, trocar nem apagar os
    // atributos de origem; e o realm não aceita atributo fora do User Profile.
    @Test
    void accountApi_OwnUserCannotSeeCreateChangeOrRemoveTheOriginAttributes() throws Exception {
        applyUserProfile();
        assertThat(realm.admin().users().userProfile().getConfiguration().getUnmanagedAttributePolicy()).isNull();
        assertThat(realm.admin().toRepresentation().isRegistrationAllowed()).isFalse();
        String userId = createUserWithAttributes("origin.selfedit", Map.of("importLineId", List.of("line-original")));
        String token = ownAccountToken(userId, "origin.selfedit");
        String accountUrl = realm.getBaseUrl() + "/account";

        HttpGet get = new HttpGet(accountUrl);
        get.setHeader("Authorization", "Bearer " + token);
        get.setHeader("Accept", "application/json");
        HttpResponse profile = httpClient.execute(get);
        String profileBody = EntityUtils.toString(profile.getEntity());
        assertThat(profile.getStatusLine().getStatusCode()).isEqualTo(200);
        assertThat(profileBody).contains("origin.selfedit").doesNotContain("line-original");

        String payload = """
                {"username":"origin.selfedit","email":"origin.selfedit@example.com",
                "firstName":"Origin","lastName":"Test","attributes":%s}""";
        // Controle positivo: o mesmo formato, sem tocar nos atributos de origem, é aceito. Sem ele a prova abaixo
        // passaria mesmo que todo payload fosse recusado.
        assertThat(postAccount(accountUrl, token, payload.formatted("{}"))).isEqualTo(204);

        for (String attributes : List.of(
                """
                {"legacyUserId":["7c1d4a52-2222-4333-8444-a55566667777"]}""",
                """
                {"importLineId":["line-forged"]}""",
                """
                {"importLineId":[]}""",
                "{}")) {
            // 204 (campos ignorados) ou 400 (campo recusado); o que importa é o atributo não mudar.
            assertThat(postAccount(accountUrl, token, payload.formatted(attributes))).isIn(204, 400);

            assertThat(realm.admin().users().get(userId).toRepresentation().getAttributes())
                    .as("atributos depois de " + attributes)
                    .containsEntry("importLineId", List.of("line-original"))
                    .doesNotContainKey("legacyUserId");
        }
    }

    // Spec 001 do graylog (K01): a policy logs:investigate do Cadastro lê a role do token; o token não ganha dado pessoal.
    @Test
    void cadastroToken_WhenUserIsLogInvestigator_CarriesTheRoleAndNoPersonalData() {
        assertThat(realm.admin().clients().get(cadastroClient().getId()).getScopeMappings().realmLevel().listAll())
                .extracting(RoleRepresentation::getName)
                .containsExactlyInAnyOrder("admin", "user", "log-investigator", "user-importer");
        UserRepresentation user = UserBuilder.create("token.investigator")
                .email("token.investigator@example.com").name("Token", "Investigator").enabled(true).build();
        String userId;
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
            userId = realm.admin().users().search("token.investigator", true).getFirst().getId();
        }
        realm.admin().users().get(userId).roles().realmLevel()
                .add(List.of(realm.admin().roles().get("log-investigator").toRepresentation()));

        AccessToken token = realm.admin().clients().get(cadastroClient().getId()).clientScopesEvaluate()
                .generateAccessToken("openid", userId, null);

        assertThat(token.getRealmAccess().getRoles()).contains("log-investigator", "user");
        assertThat(token.getPreferredUsername()).isEqualTo("token.investigator");
        assertThat(token.getEmail()).isNull();
        assertThat(token.getName()).isNull();
        assertThat(token.getGivenName()).isNull();
        assertThat(token.getFamilyName()).isNull();
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
        applyUserProfile();
        UPConfig applied = realm.admin().users().userProfile().getConfiguration();

        assertThat(applied.getAttributes()).extracting(UPAttribute::getName).contains(
                "mobilePhone", "postalCode", "street", "addressNumber", "complement", "district", "city",
                "cityIbgeCode", "state", "legacyUserId");
        assertThat(applied.getAttributes())
                .allSatisfy(attribute -> assertThat(attribute.getPermissions().getEdit()).containsExactly("admin"));
        assertThat(applied.getAttribute("legacyUserId").getPermissions().getView()).containsExactly("admin");
        assertThat(applied.getAttribute("importLineId").getPermissions().getView()).containsExactly("admin");
    }

    private static boolean hasOriginMapper(List<ProtocolMapperRepresentation> mappers) {
        return mappers != null && mappers.stream().anyMatch(m -> ORIGIN_MAPPER.equals(m.getProtocolMapper()));
    }

    private void applyUserProfile() throws IOException {
        try (InputStream file = getClass().getResourceAsStream("/workspace-user-profile.json")) {
            realm.admin().users().userProfile().update(JsonSerialization.readValue(file, UPConfig.class));
        }
    }

    private String createUserWithAttributes(String username, Map<String, List<String>> attributes) {
        UserRepresentation user = UserBuilder.create(username)
                .email(username + "@example.com").name("Origin", "Test").enabled(true).build();
        user.setEmailVerified(true);
        user.setAttributes(attributes);
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
        return realm.admin().users().search(username, true).getFirst().getId();
    }

    // Token do próprio usuário, obtido pelo login real na Account Console (Authorization Code + PKCE).
    private String ownAccountToken(String userId, String username) throws Exception {
        setPassword(userId);
        BrowserFlow browser = new BrowserFlow(httpClient, realm.getBaseUrl(), keycloakUrls.getBase(),
                "account-console");
        BrowserFlow.Page page = browser.login(username, PASSWORD);
        return exchangeCode(page.location(), browser.redirectUri(),
                new BasicNameValuePair("client_id", "account-console"));
    }

    private void setPassword(String userId) {
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(PASSWORD);
        realm.admin().users().get(userId).resetPassword(password);
    }

    // Troca o code do redirecionamento pelo access token; "client" identifica (e autentica, se confidencial) o client.
    private String exchangeCode(String location, String redirectUri, NameValuePair... client) throws IOException {
        Matcher code = AUTHORIZATION_CODE.matcher(String.valueOf(location));
        assertThat(code.find()).as("code no redirecionamento do login").isTrue();
        List<NameValuePair> form = new ArrayList<>(List.of(client));
        form.add(new BasicNameValuePair("grant_type", "authorization_code"));
        form.add(new BasicNameValuePair("code", code.group(1)));
        form.add(new BasicNameValuePair("redirect_uri", redirectUri));
        form.add(new BasicNameValuePair("code_verifier", CODE_VERIFIER));
        HttpPost post = new HttpPost(realm.getBaseUrl() + "/protocol/openid-connect/token");
        post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
        Matcher token = ACCESS_TOKEN.matcher(EntityUtils.toString(httpClient.execute(post).getEntity()));
        assertThat(token.find()).as("access token do usuário").isTrue();
        return token.group(1);
    }

    private int postAccount(String url, String token, String body) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setHeader("Authorization", "Bearer " + token);
        post.setHeader("Accept", "application/json");
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        HttpResponse response = httpClient.execute(post);
        EntityUtils.consume(response.getEntity());
        return response.getStatusLine().getStatusCode();
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
