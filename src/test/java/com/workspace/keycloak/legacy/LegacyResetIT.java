package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;

import com.workspace.keycloak.support.BrowserFlow;
import com.workspace.keycloak.support.BrowserFlow.Page;
import com.workspace.keycloak.support.FakeGraylog;
import com.workspace.keycloak.support.FakeLegadoServer;
import com.workspace.keycloak.support.FakeSmtp;
import com.workspace.keycloak.support.LegacyServerConfig;
import com.workspace.keycloak.support.LegadoSimulator;
import com.workspace.keycloak.support.LegadoTestServer;
import com.workspace.keycloak.support.OidcHttp;

import jakarta.ws.rs.core.Response;

/**
 * Esqueci minha senha (US-03 da spec 001 do cadastro) num Keycloak real com o realm versionado, o Legado simulado e um
 * SMTP falso. A resposta ao usuário é a mesma em todos os casos; só quem existe recebe e-mail.
 */
@KeycloakIntegrationTest(config = LegacyServerConfig.class)
class LegacyResetIT {

    private static final String CLIENT_ID = "account-console";
    private static final String STRONG_PASSWORD = "Senha-Forte-2026!";
    private static final String NEW_PASSWORD = "Nova-Senha-Forte-2027!";
    private static final String NEUTRAL_MESSAGE =
            "Se houver uma conta para esse login ou e‑mail, você receberá as instruções em instantes.";
    private static final String MISSING_USERNAME_MESSAGE = "Informe seu login ou e-mail.";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @InjectRealm(fromJson = "/realm-workspace.json")
    ManagedRealm realm;

    @InjectHttpClient
    HttpClient httpClient;

    @InjectKeycloakUrls
    KeycloakUrls keycloakUrls;

    private final FakeLegadoServer legado = LegadoTestServer.instance();
    private final FakeGraylog graylog = FakeGraylog.instance();
    private final LegadoSimulator simulator = new LegadoSimulator();
    private FakeSmtp smtp;

    @BeforeEach
    void setUp() throws Exception {
        String profile = new String(getClass().getResourceAsStream("/workspace-user-profile.json").readAllBytes(),
                StandardCharsets.UTF_8);
        realm.admin().users().userProfile().update(org.keycloak.util.JsonSerialization.readValue(profile,
                org.keycloak.representations.userprofile.config.UPConfig.class));
        graylog.reset();
        legado.requests().clear();
        simulator.reset();
        simulator.attachTo(legado);
        smtp = FakeSmtp.start();
        RealmRepresentation representation = realm.admin().toRepresentation();
        representation.setSmtpServer(Map.of("host", "localhost", "port", String.valueOf(smtp.port()),
                "from", "no-reply@workspace.local", "auth", "false", "ssl", "false", "starttls", "false"));
        realm.admin().update(representation);
    }

    @AfterEach
    void tearDown() throws IOException {
        smtp.close();
    }

    @Test
    void reset_WhenUserIsInKeycloak_SendsTheStandardEmailWithoutCallingLegado() throws Exception {
        createNativeUser("reset.native", "reset.native@example.com");

        Page page = browser().requestReset("reset.native@example.com");

        assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        assertThat(smtp.messages()).singleElement().satisfies(mail -> assertThat(mail).contains("reset.native@example.com"));
        assertThat(legado.requests()).isEmpty();
    }

    @Test
    void reset_WhenUserExistsOnlyInLegado_CreatesUserWithoutPasswordAndSendsTheEmail() throws Exception {
        simulator.account("reset.ana", "reset.ana@gmail.com", STRONG_PASSWORD, "valid", "Admin");

        Page page = browser().requestReset("Reset.Ana");

        assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        UserRepresentation user = fullUser("reset.ana");
        assertThat(user.getEmail()).isEqualTo("reset.ana@gmail.com");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getAttributes()).containsEntry("legacyUserId", List.of(LegadoSimulator.legacyId("reset.ana")));
        assertThat(realm.admin().users().get(user.getId()).credentials())
                .noneSatisfy(credential -> assertThat(credential.getType()).isEqualTo("password"));
        assertThat(realm.admin().users().get(user.getId()).roles().realmLevel().listEffective().stream()
                .map(RoleRepresentation::getName).toList()).contains("user", "admin");
        assertThat(smtp.messages()).singleElement().satisfies(mail -> assertThat(mail).contains("reset.ana@gmail.com"));
        String prepared = awaitGelf("LEGACY_USER_PREPARED_FOR_RESET");
        assertThat(prepared).contains("\"_user_id\":\"" + user.getId() + "\"",
                "\"_legacy_user_id\":\"" + LegadoSimulator.legacyId("reset.ana") + "\"");
    }

    // Ao abrir o link o usuário define a senha e entra; só então o Legado é marcado (RN-07), com o id do Keycloak.
    @Test
    void reset_WhenLegadoUserOpensTheLink_SetsPasswordLogsInAndLegadoIsMarkedAfterwards() throws Exception {
        simulator.account("reset.bia", "reset.bia@gmail.com", STRONG_PASSWORD, "valid", "user");
        BrowserFlow browser = browser();
        browser.requestReset("reset.bia@gmail.com");
        assertThat(simulator.marks()).as("nada marcado antes de a senha existir").isEmpty();

        Page updatePage = browser.openEmailLink(smtp.messages().getFirst());
        assertThat(updatePage.body()).contains("kc-passwd-update-form");
        Page done = browser.submitNewPassword(updatePage, NEW_PASSWORD);

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).contains("code=");
        String userId = fullUser("reset.bia").getId();
        await().atMost(WAIT).until(() -> simulator.marks().size() == 1);
        assertThat(simulator.marks().getFirst().legacyUserId()).isEqualTo(LegadoSimulator.legacyId("reset.bia"));
        assertThat(simulator.marks().getFirst().body()).contains(userId);
        assertThat(awaitGelf("LEGACY_MIGRATION_MARKED")).contains("\"_user_id\":\"" + userId + "\"");
        // Já com senha, o login seguinte é nativo.
        assertThat(OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), CLIENT_ID, browser.redirectUri(),
                "reset.bia", NEW_PASSWORD).location()).contains("code=");
    }

    // Cenário da spec: a "casca" criada pelo reset entra com a senha antiga do Legado (migração do login, T15).
    @Test
    void login_WhenShellCreatedByResetUsesTheLegacyPassword_MigratesAndMarksLegado() throws Exception {
        simulator.account("reset.caio", "reset.caio@gmail.com", STRONG_PASSWORD, "valid", "user");
        browser().requestReset("reset.caio");
        String shellId = fullUser("reset.caio").getId();

        OidcHttp.Response login = OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), CLIENT_ID,
                keycloakUrls.getBase() + "/realms/workspace/account/", "reset.caio", STRONG_PASSWORD);

        assertThat(login.location()).contains("code=");
        assertThat(realm.admin().users().search("reset.caio", true)).singleElement()
                .satisfies(user -> assertThat(user.getId()).isEqualTo(shellId));
        assertThat(realm.admin().users().get(shellId).credentials())
                .anySatisfy(credential -> assertThat(credential.getType()).isEqualTo("password"));
        await().atMost(WAIT).until(() -> simulator.marks().size() == 1);
    }

    @Test
    void reset_WhenUserExistsNowhere_ShowsTheSameMessageSendsNothingAndAuditsMaskedLogin() throws Exception {
        Page page = browser().requestReset("ana.silva@gmail.com");

        assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        assertThat(smtp.messages()).isEmpty();
        assertThat(realm.admin().users().search("ana.silva", true)).isEmpty();
        assertThat(awaitGelf("RESET_UNKNOWN_USER")).contains("\"_masked_login\":\"ana***@gmail\"", "\"_flow\":\"reset\"");
    }

    @Test
    void reset_WhenLegadoIsDown_ShowsTheSameMessageSendsNothingAndAuditsUnavailableAsError() throws Exception {
        Page page = browser().requestReset("falha.legado");

        assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        assertThat(smtp.messages()).isEmpty();
        assertThat(awaitGelf("LEGACY_UNAVAILABLE")).contains("\"level\":3", "\"_flow\":\"reset\"",
                "\"_error\":\"http_5xx\"", "\"_masked_login\":\"fal***\"");
    }

    @Test
    void reset_WhenLegadoUserIsAlreadyMigratedAndMissingInKeycloak_SendsNothing() throws Exception {
        simulator.account("reset.done", "reset.done@gmail.com", STRONG_PASSWORD, "migrated", "user");

        Page page = browser().requestReset("reset.done");

        assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        assertThat(smtp.messages()).isEmpty();
        assertThat(realm.admin().users().search("reset.done", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_ALREADY_MIGRATED")).contains("\"_flow\":\"reset\"");
    }

    @Test
    void reset_WhenLoginIsEmpty_AsksForTheLogin() throws Exception {
        Page page = browser().requestReset("");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains(MISSING_USERNAME_MESSAGE).doesNotContain(NEUTRAL_MESSAGE);
        assertThat(smtp.messages()).isEmpty();
        assertThat(legado.requests()).isEmpty();
    }

    // Anti-enumeração: a resposta (status e mensagem) é a mesma para existente, só-Legado, inexistente e Legado fora.
    @Test
    void reset_ResponseIsTheSameInEveryCase() throws Exception {
        createNativeUser("same.native", "same.native@example.com");
        simulator.account("same.legacy", "same.legacy@gmail.com", STRONG_PASSWORD, "valid", "user");

        List<Page> pages = List.of(browser().requestReset("same.native"), browser().requestReset("same.legacy"),
                browser().requestReset("same.nobody"), browser().requestReset("falha.same"));

        assertThat(pages).allSatisfy(page -> {
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        });
        assertThat(pages.stream().map(page -> alertText(page.body())).distinct()).hasSize(1);
    }

    // LGPD: nenhum login/e-mail completo nos eventos enviados ao Graylog.
    @Test
    void auditEvents_NeverCarryFullLoginOrEmail() throws Exception {
        simulator.account("lgpd.reset", "lgpd.reset@gmail.com", STRONG_PASSWORD, "valid", "user");
        browser().requestReset("lgpd.reset@gmail.com");
        browser().requestReset("lgpd.ninguem@gmail.com");
        awaitGelf("RESET_UNKNOWN_USER");
        awaitGelf("LEGACY_USER_PREPARED_FOR_RESET");

        String all = String.join("\n", graylog.received());

        assertThat(all).contains("\"_masked_login\":\"lgp***@gmail\"").doesNotContain("lgpd.reset", "lgpd.ninguem");
    }

    private BrowserFlow browser() {
        return new BrowserFlow(httpClient, realm.getBaseUrl(), keycloakUrls.getBase(), CLIENT_ID);
    }

    // Texto do alerta da página, sem os tokens de sessão que mudam a cada requisição.
    private static String alertText(String body) {
        int start = body.indexOf("Se houver uma conta");
        return body.substring(start, body.indexOf('.', start));
    }

    private void createNativeUser(String username, String email) {
        UserRepresentation user = UserBuilder.create(username).email(email).name("Native", "User")
                .emailVerified(true).enabled(true).build();
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(STRONG_PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
    }

    private UserRepresentation fullUser(String username) {
        String id = realm.admin().users().search(username, true).getFirst().getId();
        return realm.admin().users().get(id).toRepresentation();
    }

    private String awaitGelf(String type) {
        String marker = "\"_type\":\"" + type + "\"";
        await().atMost(WAIT).until(() -> graylog.received().stream().anyMatch(gelf -> gelf.contains(marker)));
        return graylog.received().stream().filter(gelf -> gelf.contains(marker)).findFirst().orElseThrow();
    }
}
