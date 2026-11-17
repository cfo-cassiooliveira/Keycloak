package com.workspace.keycloak.realm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
import org.junit.jupiter.api.Test;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;
import org.keycloak.util.JsonSerialization;

import com.workspace.keycloak.support.FakeSmtp;
import com.workspace.keycloak.support.WorkspaceServerConfig;

/**
 * Cenários de contorno do OTP obrigatório da role log-investigator (K01 da spec 001 do graylog).
 * Realm próprio, importado só do JSON versionado: nenhum client criado por outros testes entra nas verificações.
 *
 * <p>Contrato com o Cadastro: a pesquisa em logs exige a role <b>e</b> {@code acr=otp} no token. O {@code acr=otp}
 * só sai quando o OTP foi digitado nesta autenticação (step-up por LoA); SSO por cookie ou refresh nunca o produzem.</p>
 */
@KeycloakIntegrationTest(config = WorkspaceServerConfig.class)
class LogInvestigatorOtpBypassIT {

    private static final String PASSWORD = "Workspace-Strong-1!";
    private static final String LOG_INVESTIGATOR = "log-investigator";
    private static final String OTP_ACR = "otp";
    private static final String CLIENT_ID = "cadastro";
    private static final String REDIRECT_URI = "http://localhost:4300/bff/signin-oidc";
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    // Par do exemplo da RFC 7636: o teste troca o code por tokens.
    private static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("<form id=\"kc-form-login\" action=\"([^\"]+)\"");
    private static final Pattern OTP_FORM_ACTION = Pattern.compile("<form id=\"kc-otp-login-form\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern RESET_LINK = Pattern.compile("href=\"([^\"]{0,500}/login-actions/reset-credentials[^\"]{0,500})\"");
    private static final Pattern RESET_FORM_ACTION =
            Pattern.compile("<form id=\"kc-reset-password-form\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern ACTION_TOKEN_LINK = Pattern.compile("(http[^\\s\"<>]*/login-actions/action-token\\?[^\\s\"<>]*)");
    private static final Pattern JSON_FIELD = Pattern.compile("\"%s\":\"([^\"]+)\"");

    @InjectRealm(fromJson = "/realm-workspace.json")
    ManagedRealm realm;

    @InjectHttpClient
    HttpClient httpClient;

    @InjectKeycloakUrls
    KeycloakUrls keycloakUrls;

    // Último código TOTP enviado neste teste: o Keycloak não aceita o mesmo código duas vezes.
    private String lastTotp;

    // Sem password grant em nenhum client versionado: o fluxo "direct grant" padrão não exige OTP pela role.
    @Test
    void versionedClients_AllowNoPasswordGrantAndDoNotOverrideTheBrowserFlow() {
        List<ClientRepresentation> clients = realm.admin().clients().findAll();

        assertThat(clients).isNotEmpty().allSatisfy(client -> {
            assertThat(client.isDirectAccessGrantsEnabled()).as(client.getClientId() + " direct grant").isFalse();
            Map<String, String> overrides = client.getAuthenticationFlowBindingOverrides();
            assertThat(overrides == null ? Map.of() : overrides).as(client.getClientId() + " flow overrides").isEmpty();
        });
    }

    // Ajuste da versão do QA: o SSO sem acr continua emitindo code (pedir OTP a cada SSO não é o modelo do Keycloak);
    // o que protege é o step-up. Pedido com acr=otp, para quem recebeu a role com a sessão já aberta, exige o OTP.
    @Test
    void ssoLogin_WhenRoleIsGrantedAfterSessionWasOpened_StillRequiresOtp() throws Exception {
        String userId = createUser("sso.before.role", false);
        Map<String, String> cookies = new LinkedHashMap<>();
        Page afterPassword = postLogin(send(authorizationGet(null), cookies), "sso.before.role", cookies);
        assertThat(afterPassword.location()).as("login inicial sem a role conclui sem OTP").contains("code=");

        grantLogInvestigator(userId);
        Page stepUp = follow(send(authorizationGet(OTP_ACR), cookies), cookies);

        assertThat(stepUp.location() == null || !stepUp.location().contains("code="))
                .as("SSO devolveu code com acr=otp sem OTP: " + stepUp.location()).isTrue();
        assertThat(stepUp.body()).contains("id=\"kc-totp-settings\"");
    }

    // O token sem OTP nesta autenticação (SSO por cookie e refresh de token antigo) traz a role, mas nunca acr=otp.
    @Test
    void tokens_WhenOtpWasNotEnteredInThisAuthentication_DoNotCarryOtpAcr() throws Exception {
        String userId = createUser("no.otp.token", false);
        Map<String, String> cookies = new LinkedHashMap<>();
        Page login = postLogin(send(authorizationGet(null), cookies), "no.otp.token", cookies);
        String refreshToken = jsonField(exchangeCode(login.location()), "refresh_token");

        grantLogInvestigator(userId);
        AccessToken refreshed = accessToken(refresh(refreshToken));
        AccessToken sso = accessToken(exchangeCode(send(authorizationGet(null), cookies).location()));

        assertThat(refreshed.getRealmAccess().getRoles()).contains(LOG_INVESTIGATOR);
        assertThat(refreshed.getAcr()).isNotEqualTo(OTP_ACR);
        assertThat(sso.getRealmAccess().getRoles()).contains(LOG_INVESTIGATOR);
        assertThat(sso.getAcr()).isNotEqualTo(OTP_ACR);
    }

    // Login normal do investigador: OTP pela role, mas acr=password; ao entrar na pesquisa, o step-up pede o OTP de novo.
    @Test
    void stepUp_AfterLoginWithRoleOtp_RequiresOtpAgainAndIssuesOtpAcr() throws Exception {
        createUser("step.up.investigator", true);
        Map<String, String> cookies = new LinkedHashMap<>();
        Page otpPage = postLogin(send(authorizationGet(null), cookies), "step.up.investigator", cookies);
        AccessToken loginToken = accessToken(exchangeCode(postOtp(otpPage, cookies).location()));

        Page stepUpPage = send(authorizationGet(OTP_ACR), cookies);
        AccessToken stepUpToken = accessToken(exchangeCode(postOtp(stepUpPage, cookies).location()));

        assertThat(loginToken.getAcr()).isEqualTo("password");
        assertThat(stepUpToken.getAcr()).isEqualTo(OTP_ACR);
    }

    // Janela do acr=otp (contrato da C03): o refresh de um token do step-up mantém acr=otp pela sessão do client, mas o
    // auth_time fica o do step-up. Por isso a policy exige também auth_time recente, e não só o acr.
    @Test
    void refresh_AfterStepUp_KeepsOtpAcrAndStepUpAuthTime() throws Exception {
        createUser("refresh.investigator", true);
        Map<String, String> cookies = new LinkedHashMap<>();
        Page otpPage = postLogin(send(authorizationGet(null), cookies), "refresh.investigator", cookies);
        AccessToken loginToken = accessToken(exchangeCode(postOtp(otpPage, cookies).location()));
        String stepUpResponse = exchangeCode(postOtp(send(authorizationGet(OTP_ACR), cookies), cookies).location());

        AccessToken refreshed = accessToken(refresh(jsonField(stepUpResponse, "refresh_token")));

        long stepUpAuthTime = accessToken(stepUpResponse).getAuth_time();
        assertThat(refreshed.getAcr()).isEqualTo(OTP_ACR);
        assertThat(stepUpAuthTime).isGreaterThan(loginToken.getAuth_time());
        assertThat(refreshed.getAuth_time()).isEqualTo(stepUpAuthTime);
    }

    // Entrada direta pela pesquisa (acr=otp no primeiro login): senha e um único OTP.
    @Test
    void firstLogin_WithOtpAcr_AsksOtpOnceAndIssuesOtpAcr() throws Exception {
        createUser("direct.investigator", true);
        Map<String, String> cookies = new LinkedHashMap<>();
        Page otpPage = postLogin(send(authorizationGet(OTP_ACR), cookies), "direct.investigator", cookies);

        Page afterOtp = postOtp(otpPage, cookies);

        assertThat(afterOtp.location()).as("um segundo OTP foi pedido").contains("code=");
        assertThat(accessToken(exchangeCode(afterOtp.location())).getAcr()).isEqualTo(OTP_ACR);
    }

    // O reset de senha conclui o login: para log-investigator, o fluxo de reset também precisa exigir o OTP pela role.
    @Test
    void resetCredentialsFlow_RequiresOtpForLogInvestigator() {
        RealmRepresentation representation = realm.admin().toRepresentation();
        String resetFlow = representation.getResetCredentialsFlow();

        List<AuthenticationExecutionInfoRepresentation> executions =
                realm.admin().flows().getExecutions(resetFlow);

        assertThat(executions)
                .as("fluxo " + resetFlow + " sem condição pela role log-investigator")
                .anySatisfy(execution -> assertThat(execution.getProviderId()).isEqualTo("conditional-user-role"));
    }

    // Reset de verdade (e-mail com o link): depois do link, o investigador precisa digitar o OTP que já tem.
    @Test
    void passwordReset_WhenUserIsLogInvestigator_AsksOtpAfterTheEmailLink() throws Exception {
        createUser("reset.investigator", true);
        try (FakeSmtp smtp = FakeSmtp.start()) {
            RealmRepresentation representation = realm.admin().toRepresentation();
            Map<String, String> originalSmtp = representation.getSmtpServer();
            representation.setSmtpServer(Map.of("host", "localhost", "port", String.valueOf(smtp.port()),
                    "from", "no-reply@workspace.local", "auth", "false", "ssl", "false", "starttls", "false"));
            realm.admin().update(representation);
            try {
                Map<String, String> cookies = new LinkedHashMap<>();
                Page resetPage = send(new HttpGet(absolute(find(RESET_LINK, send(authorizationGet(null), cookies)))), cookies);
                HttpPost chooseUser = new HttpPost(htmlUrl(find(RESET_FORM_ACTION, resetPage)));
                chooseUser.setEntity(new UrlEncodedFormEntity(
                        List.of(new BasicNameValuePair("username", "reset.investigator")), StandardCharsets.UTF_8));
                send(chooseUser, cookies);
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !smtp.messages().isEmpty());

                Page afterLink = follow(send(new HttpGet(actionTokenLink(smtp.messages().getFirst())), cookies), cookies);

                assertThat(afterLink.body()).contains("id=\"kc-otp-login-form\"");
            } finally {
                representation.setSmtpServer(originalSmtp);
                realm.admin().update(representation);
            }
        }
    }

    private String createUser(String username, boolean withRoleAndOtp) {
        UserRepresentation user = UserBuilder.create(username)
                .email(username + "@example.com").name("Test", "User").emailVerified(true).enabled(true).build();
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(withRoleAndOtp ? List.of(credential, totp()) : List.of(credential));
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
        String userId = realm.admin().users().search(username, true).getFirst().getId();
        if (withRoleAndOtp) {
            grantLogInvestigator(userId);
        }
        return userId;
    }

    private void grantLogInvestigator(String userId) {
        realm.admin().users().get(userId).roles().realmLevel()
                .add(List.of(realm.admin().roles().get(LOG_INVESTIGATOR).toRepresentation()));
    }

    // Credencial TOTP no formato de importação do Keycloak (segredo fixo, só de teste).
    private static CredentialRepresentation totp() {
        CredentialRepresentation otp = new CredentialRepresentation();
        otp.setType("otp");
        otp.setUserLabel("test");
        otp.setSecretData("{\"value\":\"" + TOTP_SECRET + "\"}");
        otp.setCredentialData("{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}");
        return otp;
    }

    // Client do Cadastro (o consumidor da role): o token dele é o que a policy logs:investigate vai avaliar.
    private String cadastroSecret() {
        String id = realm.admin().clients().findByClientId(CLIENT_ID).getFirst().getId();
        return realm.admin().clients().get(id).getSecret().getValue();
    }

    private HttpGet authorizationGet(String acrValues) throws URISyntaxException {
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
        return new HttpGet(uri.build());
    }

    private Page postLogin(Page loginPage, String username, Map<String, String> cookies) throws IOException {
        HttpPost post = new HttpPost(htmlUrl(find(LOGIN_FORM_ACTION, loginPage)));
        post.setEntity(new UrlEncodedFormEntity(List.of(
                new BasicNameValuePair("username", username),
                new BasicNameValuePair("password", PASSWORD)), StandardCharsets.UTF_8));
        return send(post, cookies);
    }

    // Uma única tentativa por janela de 30 s: o Keycloak recusa reusar o mesmo código.
    private Page postOtp(Page otpPage, Map<String, String> cookies) throws IOException {
        HttpPost post = new HttpPost(htmlUrl(find(OTP_FORM_ACTION, otpPage)));
        post.setEntity(new UrlEncodedFormEntity(
                List.of(new BasicNameValuePair("otp", nextUnusedTotp())), StandardCharsets.UTF_8));
        return send(post, cookies);
    }

    private String nextUnusedTotp() {
        TimeBasedOTP generator = new TimeBasedOTP("HmacSHA1", 6, 30, 1);
        Awaitility.await().atMost(Duration.ofSeconds(35)).pollInterval(Duration.ofMillis(500))
                .until(() -> !generator.generateTOTP(TOTP_SECRET).equals(lastTotp));
        lastTotp = generator.generateTOTP(TOTP_SECRET);
        return lastTotp;
    }

    private String exchangeCode(String location) throws Exception {
        assertThat(location).as("redirecionamento com code").contains("code=");
        String code = new URIBuilder(location).getQueryParams().stream()
                .filter(param -> param.getName().equals("code")).findFirst().orElseThrow().getValue();
        return tokenRequest(List.of(
                new BasicNameValuePair("grant_type", "authorization_code"),
                new BasicNameValuePair("client_id", CLIENT_ID),
                new BasicNameValuePair("client_secret", cadastroSecret()),
                new BasicNameValuePair("code", code),
                new BasicNameValuePair("redirect_uri", REDIRECT_URI),
                new BasicNameValuePair("code_verifier", CODE_VERIFIER)));
    }

    private String refresh(String refreshToken) throws IOException {
        return tokenRequest(List.of(
                new BasicNameValuePair("grant_type", "refresh_token"),
                new BasicNameValuePair("client_id", CLIENT_ID),
                new BasicNameValuePair("client_secret", cadastroSecret()),
                new BasicNameValuePair("refresh_token", refreshToken)));
    }

    private String tokenRequest(List<BasicNameValuePair> form) throws IOException {
        HttpPost post = new HttpPost(realm.getBaseUrl() + "/protocol/openid-connect/token");
        post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
        Page response = send(post, new LinkedHashMap<>());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private static AccessToken accessToken(String tokenResponse) throws IOException {
        String payload = jsonField(tokenResponse, "access_token").split("\\.")[1];
        return JsonSerialization.readValue(Base64.getUrlDecoder().decode(payload), AccessToken.class);
    }

    private static String jsonField(String json, String field) {
        Matcher matcher = Pattern.compile(String.format(JSON_FIELD.pattern(), field)).matcher(json);
        assertThat(matcher.find()).as(field + " na resposta").isTrue();
        return matcher.group(1);
    }

    private static String find(Pattern pattern, Page page) {
        Matcher matcher = pattern.matcher(page.body());
        assertThat(matcher.find()).as(pattern + " na página").isTrue();
        return matcher.group(1);
    }

    private static String htmlUrl(String href) {
        return href.replace("&amp;", "&");
    }

    private String absolute(String href) {
        String url = htmlUrl(href);
        return url.startsWith("/") ? keycloakUrls.getBase() + url : url;
    }

    // O e-mail vem em quoted-printable: junta as quebras suaves e decodifica "=3D" antes de achar o link.
    private static String actionTokenLink(String message) {
        String decoded = message.replace("=\r\n", "").replace("=3D", "=");
        Matcher matcher = ACTION_TOKEN_LINK.matcher(decoded);
        assertThat(matcher.find()).as("link do e-mail de reset").isTrue();
        return htmlUrl(matcher.group(1));
    }

    // Segue os redirecionamentos internos do fluxo de login (login-actions) até uma página.
    private Page follow(Page page, Map<String, String> cookies) throws IOException {
        Page current = page;
        for (int hop = 0; hop < 3 && current.status() == 302 && current.location().contains("/login-actions/"); hop++) {
            current = send(new HttpGet(current.location()), cookies);
        }
        return current;
    }

    // Os cookies do Keycloak têm "Secure" e o HttpClient não os reenvia para http: guardamos e repassamos à mão.
    private Page send(HttpRequestBase request, Map<String, String> cookies) throws IOException {
        request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
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
        return new Page(response.getStatusLine().getStatusCode(), body, location == null ? null : location.getValue());
    }

    private record Page(int status, String body, String location) {
    }
}
