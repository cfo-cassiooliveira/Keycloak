package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import jakarta.ws.rs.core.Response;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import com.workspace.keycloak.support.BrowserFlow;
import com.workspace.keycloak.support.BrowserFlow.Page;
import com.workspace.keycloak.support.FakeGraylog;
import com.workspace.keycloak.support.FakeLegadoServer;
import com.workspace.keycloak.support.FakeLegadoServer.Reply;
import com.workspace.keycloak.support.FakeSmtp;
import com.workspace.keycloak.support.LegacyServerConfig;
import com.workspace.keycloak.support.LegadoSimulator;
import com.workspace.keycloak.support.LegadoTestServer;
import com.workspace.keycloak.support.OidcHttp;

/**
 * Bordas e regressões da migração sob demanda (US-01, US-02, US-03 e US-09 da spec 001 do cadastro) que os ITs
 * principais não exercitam: usuário nativo sem senha, corpo inválido do Legado, força bruta no formulário do
 * Legado, reset em paralelo e a varredura de LGPD sobre TODOS os eventos capturados.
 */
@KeycloakIntegrationTest(config = LegacyServerConfig.class)
class LegacyMigrationEdgeCasesIT {

    private static final String CLIENT_ID = "account-console";
    private static final String STRONG_PASSWORD = "Senha-Forte-2026!";
    private static final String GENERIC_ERROR = "Usuário ou senha inválidos.";
    private static final String NEUTRAL_MESSAGE =
            "Se houver uma conta para esse login ou e‑mail, você receberá as instruções em instantes.";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Set<String> MASKED_LOGIN_TYPES = Set.of("LEGACY_USER_NOT_FOUND", "LEGACY_PASSWORD_MISMATCH",
            "LEGACY_ACCOUNT_LOCKED", "LEGACY_ALREADY_MIGRATED", "RESET_UNKNOWN_USER", "LEGACY_UNAVAILABLE");
    private static final List<String> FORBIDDEN_FIELDS = List.of("_ip", "_ip_address", "_session_id", "_email",
            "_username", "_login", "_password", "_token", "_access_token", "_refresh_token");
    private static final Pattern MASKED_FORMAT = Pattern.compile("^[^@\\s*]{1,3}\\*\\*\\*(@[^@\\s.*]+)?$");

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
        realm.admin().users().userProfile().update(JsonSerialization.readValue(profile,
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
    void tearDown() throws Exception {
        smtp.close();
    }

    // RN-02: sem senha e sem legacyUserId não há vínculo com o Legado; o Legado nunca é consultado.
    @Test
    void login_WhenNativeUserHasNoPasswordAndNoLegacyId_DeniesWithoutCallingLegado() throws Exception {
        UserRepresentation user = UserBuilder.create("edge.nopass").email("edge.nopass@example.com").name("No", "Pass")
                .emailVerified(true).enabled(true).build();
        try (Response created = realm.admin().users().create(user)) {
            assertThat(created.getStatus()).isEqualTo(201);
        }

        OidcHttp.Response response = login("edge.nopass", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(legado.requests()).isEmpty();
        assertThat(graylog.received()).noneMatch(gelf -> gelf.contains("\"_type\":\"LEGACY_"));
    }

    // RN-02: casca com legacyUserId e senha errada consulta o Legado, nega e não cria credencial.
    @Test
    void login_WhenShellUsesWrongPassword_DeniesAndStaysWithoutPassword() throws Exception {
        simulator.account("edge.casca", "edge.casca@gmail.com", STRONG_PASSWORD, "valid", "user");
        UserRepresentation shell = UserBuilder.create("edge.casca").email("edge.casca@gmail.com").enabled(true).build();
        shell.setAttributes(Map.of("legacyUserId", List.of(LegadoSimulator.legacyId("edge.casca"))));
        try (Response created = realm.admin().users().create(shell)) {
            assertThat(created.getStatus()).isEqualTo(201);
        }

        OidcHttp.Response response = login("edge.casca", "Outra-Senha-2026!");

        assertThat(response.body()).contains(GENERIC_ERROR);
        String id = realm.admin().users().search("edge.casca", true).getFirst().getId();
        assertThat(realm.admin().users().get(id).credentials()).noneMatch(c -> "password".equals(c.getType()));
        assertThat(awaitGelf("LEGACY_PASSWORD_MISMATCH")).contains("\"_masked_login\":\"edg***\"");
        assertThat(legado.requests()).noneMatch(request -> "PUT".equals(request.method()));
    }

    // RN-05: corpo inválido do Legado (HTML, contrato quebrado) é falha fechada e nunca cria usuário.
    @Test
    void login_WhenLegadoReturnsGarbageBody_DeniesAndAuditsInvalidResponse() throws Exception {
        legado.responder(request -> new Reply(200, "<html>proxy error</html>"));

        OidcHttp.Response response = login("edge.garbage", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(realm.admin().users().search("edge.garbage", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_UNAVAILABLE")).contains("\"level\":3", "\"_error\":\"invalid_response\"");
    }

    @Test
    void login_WhenLegadoSaysValidWithoutUser_DeniesAndCreatesNothing() throws Exception {
        legado.responder(request -> new Reply(200, "{\"outcome\":\"valid\"}"));

        OidcHttp.Response response = login("edge.nouser", STRONG_PASSWORD);

        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(realm.admin().users().search("edge.nouser", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_UNAVAILABLE")).contains("\"_error\":\"invalid_response\"");
    }

    // RN-05 (reset): corpo inválido no lookup mantém a resposta neutra, sem e-mail e sem usuário.
    @Test
    void reset_WhenLegadoReturnsGarbageBody_ShowsTheSameMessageAndSendsNothing() throws Exception {
        legado.responder(request -> new Reply(200, "not json"));

        Page page = browser().requestReset("edge.reset.garbage");

        assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        assertThat(smtp.messages()).isEmpty();
        assertThat(realm.admin().users().search("edge.reset.garbage", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_UNAVAILABLE")).contains("\"_flow\":\"reset\"", "\"_error\":\"invalid_response\"");
    }

    // US-01: a falha de quem já está no Keycloak é contabilizada pela força bruta também neste formulário.
    @Test
    void login_WhenNativeUserFailsRepeatedly_BruteForceBlocksEvenTheCorrectPasswordAndLegadoIsNeverCalled()
            throws Exception {
        UserRepresentation user = UserBuilder.create("edge.brute").email("edge.brute@example.com").name("Brute", "Force")
                .emailVerified(true).enabled(true).build();
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(STRONG_PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        try (Response created = realm.admin().users().create(user)) {
            assertThat(created.getStatus()).isEqualTo(201);
        }

        for (int attempt = 0; attempt < 6; attempt++) {
            assertThat(login("edge.brute", "Errada-Senha-2026!").status()).isEqualTo(200);
        }
        OidcHttp.Response correct = login("edge.brute", STRONG_PASSWORD);

        assertThat(correct.status()).as("senha certa depois de bloqueio temporário não entra").isEqualTo(200);
        assertThat(correct.location()).isNull();
        assertThat(legado.requests()).isEmpty();
    }

    // RN-10: o identificador é normalizado (trim, minúsculas) antes da máscara; senha errada não vaza no evento.
    @Test
    void login_WhenIdentifierHasSpacesAndUppercase_AuditsTheNormalizedMask() throws Exception {
        simulator.account("edge.maiuscula", "edge.maiuscula@gmail.com", STRONG_PASSWORD, "valid", "user");

        OidcHttp.Response response = login("  Edge.Maiuscula@GMAIL.com  ", "Errada-Senha-2026!");

        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(awaitGelf("LEGACY_PASSWORD_MISMATCH")).contains("\"_masked_login\":\"edg***@gmail\"");
        assertThat(legado.requests()).singleElement().satisfies(request ->
                assertThat(request.body()).contains("\"login\":\"edge.maiuscula@gmail.com\""));
    }

    // US-03: dois pedidos de reset simultâneos do mesmo usuário só do Legado geram um único usuário.
    @Test
    void reset_WhenTwoRequestsRunInParallel_CreatesOnlyOneUserAndNeverFails() throws Exception {
        simulator.account("edge.paralelo", "edge.paralelo@gmail.com", STRONG_PASSWORD, "valid", "user");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Page> first = pool.submit(() -> browser().requestReset("edge.paralelo"));
            Future<Page> second = pool.submit(() -> browser().requestReset("edge.paralelo@gmail.com"));

            assertThat(List.of(first.get(), second.get())).allSatisfy(page -> {
                assertThat(page.status()).isEqualTo(200);
                assertThat(page.body()).contains(NEUTRAL_MESSAGE);
            });
        } finally {
            pool.shutdownNow();
        }

        assertThat(realm.admin().users().search("edge.paralelo", true)).hasSize(1);
        await().atMost(WAIT).until(() -> !smtp.messages().isEmpty());
        assertThat(smtp.messages()).hasSizeBetween(1, 2);
    }

    // US-03: o reset aceita login em maiúsculas com espaços (mesma normalização do login).
    @Test
    void reset_WhenIdentifierHasSpacesAndUppercase_FindsTheLegadoUser() throws Exception {
        simulator.account("edge.reset.caixa", "edge.reset.caixa@gmail.com", STRONG_PASSWORD, "valid", "user");

        Page page = browser().requestReset("  EDGE.Reset.Caixa@Gmail.COM ");

        assertThat(page.body()).contains(NEUTRAL_MESSAGE);
        assertThat(realm.admin().users().search("edge.reset.caixa", true)).hasSize(1);
        assertThat(smtp.messages()).hasSize(1);
    }

    // US-09 + RN-11: varredura de TODOS os eventos de um conjunto amplo de cenários; nada de dado pessoal.
    @Test
    void auditEvents_AcrossEveryScenario_CarryOnlyMaskedLoginInTheSixAllowedTypesAndNoSecrets() throws Exception {
        simulator.account("scan.migra", "scan.migra@gmail.com", STRONG_PASSWORD, "valid", "user");
        simulator.account("scan.errada", "scan.errada@gmail.com", STRONG_PASSWORD, "valid", "user");
        simulator.account("scan.travada", "scan.travada@gmail.com", STRONG_PASSWORD, "locked", "user");
        simulator.account("scan.feita", "scan.feita@gmail.com", STRONG_PASSWORD, "migrated", "user");
        simulator.account("scan.reset", "scan.reset@gmail.com", STRONG_PASSWORD, "valid", "user");
        List<String> secrets = List.of("scan.migra", "scan.errada", "scan.travada", "scan.feita", "scan.reset",
                "scan.ninguem", "falha.scan", "Errada-Senha-2026!", STRONG_PASSWORD, "Outra-Senha-2026!");

        login("scan.migra@gmail.com", STRONG_PASSWORD);
        login("scan.errada@gmail.com", "Errada-Senha-2026!");
        login("scan.travada", STRONG_PASSWORD);
        login("scan.feita", STRONG_PASSWORD);
        login("scan.ninguem@gmail.com", "Outra-Senha-2026!");
        login("falha.scan", STRONG_PASSWORD);
        browser().requestReset("scan.reset@gmail.com");
        browser().requestReset("scan.ninguem@gmail.com");
        browser().requestReset("falha.scan");
        for (String type : List.of("LEGACY_USER_MIGRATED", "LEGACY_PASSWORD_MISMATCH", "LEGACY_ACCOUNT_LOCKED",
                "LEGACY_ALREADY_MIGRATED", "LEGACY_USER_NOT_FOUND", "LEGACY_UNAVAILABLE", "RESET_UNKNOWN_USER",
                "LEGACY_USER_PREPARED_FOR_RESET", "LEGACY_MIGRATION_MARKED")) {
            awaitGelf(type);
        }

        for (String gelf : graylog.received()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = JsonSerialization.readValue(gelf, Map.class);
            String type = String.valueOf(event.get("_type"));
            assertThat(gelf).as("evento %s sem login, e-mail ou senha completos", type).doesNotContain(secrets.toArray(new String[0]));
            assertThat(event.keySet()).as("campos de %s", type).doesNotContainAnyElementsOf(FORBIDDEN_FIELDS);
            if (event.containsKey("_masked_login")) {
                assertThat(MASKED_LOGIN_TYPES).as("tipo %s não pode levar masked_login", type).contains(type);
                assertThat(String.valueOf(event.get("_masked_login"))).matches(MASKED_FORMAT);
                assertThat(String.valueOf(event.get("short_message"))).as("masked_login só como campo próprio")
                        .doesNotContain("***");
            }
        }
        assertThat(graylog.received()).filteredOn(gelf -> gelf.contains("\"_masked_login\"")).isNotEmpty();
    }

    // US-09: os eventos de migração levam data (occurred_at) e client (client_id); o trace_id segue a mesma regra
    // dos demais eventos (só com o tracing do Keycloak ligado).
    @Test
    void migrationEvents_CarryTheClientId() throws Exception {
        login("scan.cliente@gmail.com", STRONG_PASSWORD);

        assertThat(awaitGelf("LEGACY_USER_NOT_FOUND")).contains("\"_client_id\":\"" + CLIENT_ID + "\"",
                "\"_occurred_at\"");
    }

    private OidcHttp.Response login(String login, String password) throws Exception {
        return OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), CLIENT_ID,
                keycloakUrls.getBase() + "/realms/workspace/account/", login, password);
    }

    private BrowserFlow browser() {
        return new BrowserFlow(httpClient, realm.getBaseUrl(), keycloakUrls.getBase(), CLIENT_ID);
    }

    private String awaitGelf(String type) {
        String marker = "\"_type\":\"" + type + "\"";
        await().atMost(WAIT).until(() -> graylog.received().stream().anyMatch(gelf -> gelf.contains(marker)));
        return graylog.received().stream().filter(gelf -> gelf.contains(marker)).findFirst().orElseThrow();
    }
}
