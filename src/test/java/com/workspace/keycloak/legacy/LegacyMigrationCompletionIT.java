package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.CredentialRepresentation;
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
import com.workspace.keycloak.support.LegacyServerConfig;
import com.workspace.keycloak.support.LegadoSimulator;
import com.workspace.keycloak.support.LegadoTestServer;
import com.workspace.keycloak.support.OidcHttp;

import jakarta.ws.rs.core.Response;

/**
 * Marcação do Legado depois que o usuário passa a ter senha no Keycloak (RN-07, T17): login migrado, atualização de
 * senha obrigatória e falhas do Legado. O reset concluído é coberto pelo {@code LegacyResetIT}; o rollback, pelos testes
 * unitários do serviço (nada é marcado se a transação da migração falha) e do listener.
 */
@KeycloakIntegrationTest(config = LegacyServerConfig.class)
class LegacyMigrationCompletionIT {

    private static final String CLIENT_ID = "account-console";
    private static final String STRONG_PASSWORD = "Senha-Forte-2026!";
    private static final String WEAK_PASSWORD = "Abc-1234x";
    private static final String NEW_PASSWORD = "Nova-Senha-Forte-2027!";
    private static final String UPDATE_PASSWORD_NOTICE = "Sua senha precisa ser atualizada para a nova política de segurança.";
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
    }

    @Test
    void login_WhenUserIsMigratedNow_MarksLegadoOnceAfterTheLogin() throws Exception {
        simulator.account("mark.ana", "mark.ana@gmail.com", STRONG_PASSWORD, "valid", "user");

        OidcHttp.Response response = login("mark.ana", STRONG_PASSWORD);

        assertThat(response.location()).contains("code=");
        String userId = fullUser("mark.ana").getId();
        await().atMost(WAIT).until(() -> simulator.marks().size() == 1);
        assertThat(simulator.marks().getFirst().legacyUserId()).isEqualTo(LegadoSimulator.legacyId("mark.ana"));
        assertThat(simulator.marks().getFirst().body()).contains(userId);
        assertThat(awaitGelf("LEGACY_MIGRATION_MARKED")).contains("\"_user_id\":\"" + userId + "\"",
                "\"_legacy_user_id\":\"" + LegadoSimulator.legacyId("mark.ana") + "\"");
    }

    // Regressão (RN-07): o admin migra e abandona no cadastro do OTP; o login nunca conclui, mas a senha já existe no
    // Keycloak, então o Legado precisa ter sido marcado (antes só a conclusão do LOGIN marcava).
    @Test
    void login_WhenMigratedAdminAbandonsAtOtpSetup_StillMarksLegado() throws Exception {
        simulator.account("mark.admin", "mark.admin@gmail.com", STRONG_PASSWORD, "valid", "Admin");

        OidcHttp.Response response = login("mark.admin", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("id=\"kc-totp-settings\"");
        String userId = fullUser("mark.admin").getId();
        await().atMost(WAIT).until(() -> simulator.marks().size() == 1);
        assertThat(simulator.marks().getFirst().legacyUserId()).isEqualTo(LegadoSimulator.legacyId("mark.admin"));
        assertThat(simulator.marks().getFirst().body()).contains(userId);
    }

    // Já migrado, os logins seguintes não chamam o Legado (nem para marcar).
    @Test
    void login_AfterMigration_DoesNotMarkAgain() throws Exception {
        simulator.account("mark.bia", "mark.bia@gmail.com", STRONG_PASSWORD, "valid", "user");
        login("mark.bia", STRONG_PASSWORD);
        await().atMost(WAIT).until(() -> simulator.marks().size() == 1);
        legado.requests().clear();

        assertThat(login("mark.bia", STRONG_PASSWORD).location()).contains("code=");

        assertThat(legado.requests()).isEmpty();
        assertThat(simulator.marks()).hasSize(1);
    }

    // Senha fora da política: a tela avisa; o Legado já é marcado na migração (a senha existe) e a nova senha só repete
    // o PUT idempotente.
    @Test
    void login_WhenPasswordMustBeUpdated_ShowsTheNoticeAndMarksLegadoEvenBeforeTheNewPassword() throws Exception {
        simulator.account("mark.caio", "mark.caio@gmail.com", WEAK_PASSWORD, "valid", "user");
        BrowserFlow browser = new BrowserFlow(httpClient, realm.getBaseUrl(), keycloakUrls.getBase(), CLIENT_ID);

        Page updatePage = browser.login("mark.caio", WEAK_PASSWORD);

        assertThat(updatePage.body()).contains("kc-passwd-update-form").contains(UPDATE_PASSWORD_NOTICE);
        await().atMost(WAIT).until(() -> simulator.marks().size() == 1);
        Page done = browser.submitNewPassword(updatePage, NEW_PASSWORD);
        assertThat(done.location()).contains("code=");
        await().atMost(WAIT).until(() -> !simulator.marks().isEmpty());
        assertThat(simulator.marks()).allSatisfy(mark ->
                assertThat(mark.legacyUserId()).isEqualTo(LegadoSimulator.legacyId("mark.caio")));
    }

    @Test
    void login_WhenLegadoMarkFails_LoginStillWorksAndFailureIsAuditedAsError() throws Exception {
        simulator.account("mark.dani", "mark.dani@gmail.com", STRONG_PASSWORD, "valid", "user");
        simulator.markStatus(503);

        OidcHttp.Response response = login("mark.dani", STRONG_PASSWORD);

        assertThat(response.location()).contains("code=");
        assertThat(awaitGelf("LEGACY_MIGRATION_MARK_FAILED")).contains("\"level\":3", "\"_error\":\"http_5xx\"",
                "\"_legacy_user_id\":\"" + LegadoSimulator.legacyId("mark.dani") + "\"");
    }

    @Test
    void login_WhenLegadoAnswersConflict_AuditsMarkFailedWithConflict() throws Exception {
        simulator.account("mark.edu", "mark.edu@gmail.com", STRONG_PASSWORD, "valid", "user");
        simulator.markStatus(409);

        assertThat(login("mark.edu", STRONG_PASSWORD).location()).contains("code=");

        assertThat(awaitGelf("LEGACY_MIGRATION_MARK_FAILED")).contains("\"_error\":\"conflict\"");
    }

    // Usuário nativo (sem legacyUserId) nunca chama o Legado: nem no login, nem ao trocar a senha.
    @Test
    void nativeUser_NeverCallsLegado() throws Exception {
        UserRepresentation user = UserBuilder.create("mark.native").email("mark.native@example.com")
                .name("Native", "User").emailVerified(true).enabled(true).build();
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(STRONG_PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        try (Response created = realm.admin().users().create(user)) {
            assertThat(created.getStatus()).isEqualTo(201);
        }
        String id = realm.admin().users().search("mark.native", true).getFirst().getId();

        assertThat(login("mark.native", STRONG_PASSWORD).location()).contains("code=");
        CredentialRepresentation changed = new CredentialRepresentation();
        changed.setType(CredentialRepresentation.PASSWORD);
        changed.setValue(NEW_PASSWORD);
        changed.setTemporary(false);
        realm.admin().users().get(id).resetPassword(changed);
        assertThat(login("mark.native", NEW_PASSWORD).location()).contains("code=");

        assertThat(legado.requests()).isEmpty();
        assertThat(graylog.received()).noneMatch(gelf -> gelf.contains("LEGACY_MIGRATION_MARK"));
    }

    private OidcHttp.Response login(String login, String password) throws Exception {
        return OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), CLIENT_ID,
                keycloakUrls.getBase() + "/realms/workspace/account/", login, password);
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
