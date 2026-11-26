package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.ws.rs.core.Response;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;

import com.workspace.keycloak.support.FakeGraylog;
import com.workspace.keycloak.support.FakeLegadoServer;
import com.workspace.keycloak.support.FakeLegadoServer.Reply;
import com.workspace.keycloak.support.LegacyServerConfig;
import com.workspace.keycloak.support.LegadoTestServer;
import com.workspace.keycloak.support.OidcHttp;

/**
 * Migração sob demanda no login (US-01 e US-02 da spec 001 do cadastro) num Keycloak real com o realm versionado e o
 * Legado simulado. Cada teste usa logins próprios, então a ordem não importa.
 */
@KeycloakIntegrationTest(config = LegacyServerConfig.class)
class LegacyLoginIT {

    private static final String CLIENT_ID = "account-console";
    private static final String STRONG_PASSWORD = "Senha-Forte-2026!";
    private static final String WEAK_PASSWORD = "Abc-1234x";
    private static final String GENERIC_ERROR = "Usuário ou senha inválidos.";
    private static final Pattern LOGIN_FIELD = Pattern.compile("\"login\":\"([^\"]*)\"");
    private static final Pattern PASSWORD_FIELD = Pattern.compile("\"password\":\"([^\"]*)\"");
    private static final Duration WAIT = Duration.ofSeconds(20);

    /** Conta do Legado simulado: senha, resultado quando a senha confere e JSON do usuário. */
    private record LegacyAccount(String password, String outcome, String userJson) {
    }

    private static final Map<String, LegacyAccount> ACCOUNTS = new ConcurrentHashMap<>();

    @InjectRealm(fromJson = "/realm-workspace.json")
    ManagedRealm realm;

    @InjectHttpClient
    HttpClient httpClient;

    @InjectKeycloakUrls
    KeycloakUrls keycloakUrls;

    private final FakeLegadoServer legado = LegadoTestServer.instance();
    private final FakeGraylog graylog = FakeGraylog.instance();

    @BeforeEach
    void resetFakes() throws Exception {
        applyUserProfile();
        graylog.reset();
        legado.requests().clear();
        legado.responder(LegacyLoginIT::legadoReply);
    }

    // US-01: usuário que já está no Keycloak entra sem o Legado ser consultado.
    @Test
    void login_WhenUserIsNativeToKeycloak_DoesNotCallLegado() throws Exception {
        createNativeUser("native.user", "native.user@example.com", STRONG_PASSWORD, false);

        OidcHttp.Response response = login("native.user", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).contains("code=");
        assertThat(legado.requests()).isEmpty();
    }

    @Test
    void login_WhenNativeUserTypesWrongPassword_DoesNotCallLegadoAndShowsTheGenericError() throws Exception {
        createNativeUser("native.wrong", "native.wrong@example.com", STRONG_PASSWORD, false);

        OidcHttp.Response response = login("native.wrong", "Outra-Senha-2026!");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(legado.requests()).isEmpty();
    }

    // US-02: credenciais válidas e senha dentro da política.
    @Test
    void login_WhenLegadoConfirmsCredentials_MigratesTheUserAndCompletesTheLogin() throws Exception {
        legacyAccount("ana.souza", "ana.souza@gmail.com", STRONG_PASSWORD, "valid", "user", "Ana Souza Lima");

        OidcHttp.Response response = login("Ana.Souza@Gmail.com ", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).contains("code=");
        UserRepresentation user = fullUser("ana.souza");
        assertThat(user.getEmail()).isEqualTo("ana.souza@gmail.com");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getFirstName()).isEqualTo("Ana");
        assertThat(user.getLastName()).isEqualTo("Souza Lima");
        assertThat(user.getAttributes()).containsEntry("mobilePhone", List.of("+5511987654321"))
                .containsEntry("postalCode", List.of("01001000")).containsEntry("state", List.of("SP"))
                .containsEntry("legacyUserId", List.of(legacyId("ana.souza")));
        assertThat(user.getRequiredActions()).isEmpty();
        assertThat(realmRoles(user)).contains("user").doesNotContain("admin");
        assertThat(realm.admin().users().get(user.getId()).credentials())
                .anySatisfy(credential -> assertThat(credential.getType()).isEqualTo("password"));
        assertThat(verifications()).hasSize(1);
        assertThat(verifications().getFirst().body()).contains("ana.souza@gmail.com");
        String cadastroId = realm.admin().clients().findByClientId("cadastro").getFirst().getId();
        assertThat(realm.admin().clients().get(cadastroId).clientScopesEvaluate()
                .generateAccessToken("openid", user.getId(), null).getOtherClaims())
                .containsEntry("account_origin", "migrated");
        assertThat(awaitGelf("LEGACY_USER_MIGRATED")).contains("\"_user_id\":\"" + user.getId() + "\"",
                "\"_legacy_user_id\":\"" + legacyId("ana.souza") + "\"", "\"_password_update_required\":false");
    }

    // Depois de migrado, o segundo login é nativo: o Legado não é consultado de novo.
    @Test
    void login_AfterMigration_IsNativeAndDoesNotCallLegadoAgain() throws Exception {
        legacyAccount("segundo.login", "segundo.login@gmail.com", STRONG_PASSWORD, "valid", "user", "Segundo Login");
        login("segundo.login", STRONG_PASSWORD);
        legado.requests().clear();

        OidcHttp.Response second = login("segundo.login@gmail.com", STRONG_PASSWORD);

        assertThat(second.status()).isEqualTo(302);
        assertThat(legado.requests()).isEmpty();
    }

    // RN-04: senha fora da política migra e exige atualização no mesmo login.
    @Test
    void login_WhenPasswordViolatesRealmPolicy_MigratesAndRequiresPasswordUpdate() throws Exception {
        legacyAccount("bruno.lima", "bruno.lima@gmail.com", WEAK_PASSWORD, "valid", "user", "Bruno Lima");

        OidcHttp.Response response = login("bruno.lima", WEAK_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("kc-passwd-update-form");
        UserRepresentation user = fullUser("bruno.lima");
        assertThat(user.getRequiredActions()).containsExactly("UPDATE_PASSWORD");
        assertThat(realm.admin().users().get(user.getId()).credentials())
                .anySatisfy(credential -> assertThat(credential.getType()).isEqualTo("password"));
        assertThat(awaitGelf("LEGACY_USER_MIGRATED")).contains("\"_password_update_required\":true");
    }

    @Test
    void login_WhenLegadoPasswordDoesNotMatch_ShowsGenericErrorAndCreatesNothing() throws Exception {
        legacyAccount("carla.mendes", "carla.mendes@gmail.com", STRONG_PASSWORD, "valid", "user", "Carla Mendes");

        OidcHttp.Response response = login("carla.mendes@gmail.com", "Senha-Errada-2026!");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(realm.admin().users().search("carla.mendes", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_PASSWORD_MISMATCH")).contains("\"_masked_login\":\"car***@gmail\"");
    }

    @Test
    void login_WhenUserExistsNowhere_ShowsGenericErrorAndAuditsNotFound() throws Exception {
        OidcHttp.Response response = login("ana.silva@gmail.com", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(realm.admin().users().search("ana.silva", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_USER_NOT_FOUND")).contains("\"_masked_login\":\"ana***@gmail\"");
    }

    @Test
    void login_WhenLegadoAccountIsLocked_IsDeniedWithTheGenericError() throws Exception {
        legacyAccount("diego.lock", "diego.lock@gmail.com", STRONG_PASSWORD, "locked", "user", "Diego Lock");

        OidcHttp.Response response = login("diego.lock", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(realm.admin().users().search("diego.lock", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_ACCOUNT_LOCKED")).contains("\"_masked_login\":\"die***\"");
    }

    @Test
    void login_WhenLegadoSaysAlreadyMigrated_IsDeniedWithTheGenericError() throws Exception {
        legacyAccount("elisa.done", "elisa.done@gmail.com", STRONG_PASSWORD, "migrated", "user", "Elisa Done");

        OidcHttp.Response response = login("elisa.done", STRONG_PASSWORD);

        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(realm.admin().users().search("elisa.done", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_ALREADY_MIGRATED")).contains("\"_masked_login\":\"eli***\"");
    }

    @Test
    void login_WhenLegadoFails_DeniesWithGenericErrorAndAuditsUnavailableAsError() throws Exception {
        OidcHttp.Response response = login("falha.legado", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        String gelf = awaitGelf("LEGACY_UNAVAILABLE");
        assertThat(gelf).contains("\"level\":3", "\"_error\":\"http_5xx\"", "\"_masked_login\":\"fal***\"");
    }

    @Test
    void login_WhenLegadoExceedsTimeout_DeniesWithGenericErrorAndAuditsTimeout() throws Exception {
        OidcHttp.Response response = login("lento.legado", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(awaitGelf("LEGACY_UNAVAILABLE")).contains("\"_error\":\"timeout\"");
    }

    // Usuário que já está no Keycloak continua entrando com o Legado fora do ar.
    @Test
    void login_WhenLegadoIsDown_NativeUsersStillLogIn() throws Exception {
        createNativeUser("native.offline", "native.offline@example.com", STRONG_PASSWORD, false);
        legado.responder(request -> new Reply(503, "{}"));

        OidcHttp.Response response = login("native.offline", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(302);
        assertThat(legado.requests()).isEmpty();
    }

    // RN-03 e RN-12: admin do Legado migra com as roles admin e user e precisa configurar o OTP.
    @Test
    void login_WhenLegacyAdminMigrates_GetsAdminRolesAndMustSetUpOtp() throws Exception {
        legacyAccount("admin.legado", "admin.legado@gmail.com", STRONG_PASSWORD, "valid", "Admin", "Admin Legado");

        OidcHttp.Response response = login("admin.legado", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("id=\"kc-totp-settings\"");
        UserRepresentation user = realm.admin().users().search("admin.legado", true).getFirst();
        assertThat(realmRoles(user)).contains("admin", "user");
    }

    // Conta "casca" criada pelo reset (sem senha, com legacyUserId) é completada, não duplicada.
    @Test
    void login_WhenShellAccountHasNoPassword_CompletesTheSameUser() throws Exception {
        legacyAccount("casca.reset", "casca.reset@gmail.com", STRONG_PASSWORD, "valid", "user", "Casca Reset");
        UserRepresentation shell = UserBuilder.create("casca.reset").email("casca.reset@gmail.com").enabled(true).build();
        shell.setAttributes(Map.of("legacyUserId", List.of(legacyId("casca.reset"))));
        String shellId;
        try (Response created = realm.admin().users().create(shell)) {
            assertThat(created.getStatus()).isEqualTo(201);
            shellId = realm.admin().users().search("casca.reset", true).getFirst().getId();
        }

        OidcHttp.Response response = login("casca.reset", STRONG_PASSWORD);

        assertThat(response.status()).isEqualTo(302);
        assertThat(realm.admin().users().search("casca.reset", true)).singleElement()
                .satisfies(user -> assertThat(user.getId()).isEqualTo(shellId));
        assertThat(realm.admin().users().get(shellId).credentials())
                .anySatisfy(credential -> assertThat(credential.getType()).isEqualTo("password"));
        assertThat(verifications()).hasSize(1);
    }

    // Dado do Legado que o User Profile recusa (celular inválido): login negado, nada criado, diagnóstico no evento.
    @Test
    void login_WhenLegacyDataFailsUserProfile_DeniesAndCreatesNothing() throws Exception {
        legacyAccount("perfil.ruim", "perfil.ruim@gmail.com", STRONG_PASSWORD, "valid", "user", "Perfil Ruim");
        ACCOUNTS.put("perfil.ruim", new LegacyAccount(STRONG_PASSWORD, "valid",
                userJson("perfil.ruim", "perfil.ruim@gmail.com", "Perfil Ruim", "user", "123")));

        OidcHttp.Response response = login("perfil.ruim", STRONG_PASSWORD);

        assertThat(response.body()).contains(GENERIC_ERROR);
        assertThat(realm.admin().users().search("perfil.ruim", true)).isEmpty();
        assertThat(awaitGelf("LEGACY_UNAVAILABLE")).contains("\"_error\":\"invalid_profile\"");
    }

    // Dois logins simultâneos do mesmo usuário do Legado resultam em um único usuário no Keycloak.
    @Test
    void login_WhenTwoLoginsRunAtTheSameTime_CreatesOnlyOneUser() throws Exception {
        legacyAccount("dupla.corrida", "dupla.corrida@gmail.com", STRONG_PASSWORD, "valid", "user", "Dupla Corrida");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<OidcHttp.Response> attempt = () -> login("dupla.corrida", STRONG_PASSWORD);
            Future<OidcHttp.Response> first = pool.submit(attempt);
            Future<OidcHttp.Response> second = pool.submit(attempt);

            OidcHttp.Response a = first.get();
            OidcHttp.Response b = second.get();

            assertThat(List.of(a.status(), b.status())).as("nenhum erro 5xx").allMatch(status -> status < 500);
            assertThat(List.of(a, b)).anySatisfy(r -> assertThat(r.location()).contains("code="));
        } finally {
            pool.shutdownNow();
        }
        assertThat(realm.admin().users().search("dupla.corrida", true)).hasSize(1);
        assertThat(login("dupla.corrida", STRONG_PASSWORD).location()).contains("code=");
    }

    // LGPD: nenhum login/e-mail completo nem senha nos eventos enviados ao Graylog.
    @Test
    void auditEvents_NeverCarryFullLoginEmailOrPassword() throws Exception {
        legacyAccount("lgpd.teste", "lgpd.teste@gmail.com", STRONG_PASSWORD, "valid", "user", "Lgpd Teste");
        login("lgpd.teste@gmail.com", STRONG_PASSWORD);
        login("lgpd.inexistente@gmail.com", "Outra-Senha-2026!");
        login("lgpd.teste@gmail.com", "Senha-Errada-2026!");
        awaitGelf("LEGACY_USER_NOT_FOUND");

        String all = String.join("\n", graylog.received());

        assertThat(all).contains("LEGACY_USER_MIGRATED").contains("\"_masked_login\":\"lgp***@gmail\"")
                .doesNotContain("lgpd.teste", "lgpd.inexistente", STRONG_PASSWORD, "Outra-Senha-2026!",
                        "Senha-Errada-2026!");
    }

    // Só as verificações de credenciais: a marcação (PUT) ao fim do login migrado é coberta pelo LegacyMigrationCompletionIT.
    private List<FakeLegadoServer.Received> verifications() {
        return legado.requests().stream().filter(request -> "POST".equals(request.method())).toList();
    }

    // ---- Legado simulado ----

    private static Reply legadoReply(FakeLegadoServer.Received request) {
        if ("PUT".equals(request.method())) {
            return new Reply(204, "");
        }
        String login = LegacyUserMapper.normalizeLogin(group(LOGIN_FIELD, request.body()));
        String password = group(PASSWORD_FIELD, request.body());
        if (login.startsWith("falha.")) {
            return new Reply(503, "{}");
        }
        if (login.startsWith("lento.")) {
            sleep(1500);
            return new Reply(200, "{\"outcome\":\"not_found\"}");
        }
        LegacyAccount account = ACCOUNTS.get(login);
        if (account == null) {
            return new Reply(200, "{\"outcome\":\"not_found\"}");
        }
        if (!account.password().equals(password)) {
            return new Reply(200, "{\"outcome\":\"invalid_password\"}");
        }
        return "valid".equals(account.outcome())
                ? new Reply(200, "{\"outcome\":\"valid\",\"user\":" + account.userJson() + "}")
                : new Reply(200, "{\"outcome\":\"" + account.outcome() + "\"}");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String group(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void legacyAccount(String userName, String email, String password, String outcome, String role,
            String fullName) {
        LegacyAccount account = new LegacyAccount(password, outcome, userJson(userName, email, fullName, role, "11987654321"));
        ACCOUNTS.put(userName, account);
        ACCOUNTS.put(email, account);
    }

    // Id estável por usuário (UUID derivado do nome) para conferir o atributo legacyUserId.
    private static String legacyId(String userName) {
        return java.util.UUID.nameUUIDFromBytes(userName.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String userJson(String userName, String email, String fullName, String role, String phone) {
        return """
                {"id":"%s","userName":"%s","email":"%s","emailConfirmed":true,"fullName":"%s","mobilePhone":"%s",
                 "address":{"postalCode":"01001-000","street":"Praça da Sé","number":"1","complement":null,
                            "district":"Sé","city":"São Paulo","cityIbgeCode":3550308,"state":"SP"},
                 "roles":["%s"]}""".formatted(legacyId(userName), userName, email, fullName, phone, role);
    }

    // ---- Keycloak ----

    // O realm importado não traz o User Profile: o provisioner o aplica em produção e aqui o teste faz o mesmo.
    private void applyUserProfile() throws Exception {
        String json = new String(getClass().getResourceAsStream("/workspace-user-profile.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        realm.admin().users().userProfile().update(org.keycloak.util.JsonSerialization.readValue(json,
                org.keycloak.representations.userprofile.config.UPConfig.class));
    }

    private OidcHttp.Response login(String login, String password) throws Exception {
        return OidcHttp.submitLoginForm(httpClient, realm.getBaseUrl(), CLIENT_ID,
                keycloakUrls.getBase() + "/realms/workspace/account/", login, password);
    }

    private void createNativeUser(String username, String email, String password, boolean admin) {
        UserRepresentation user = UserBuilder.create(username).email(email).name("Native", "User")
                .emailVerified(true).enabled(true).build();
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
        if (admin) {
            String id = realm.admin().users().search(username, true).getFirst().getId();
            realm.admin().users().get(id).roles().realmLevel()
                    .add(List.of(realm.admin().roles().get("admin").toRepresentation()));
        }
    }

    // A busca devolve a representação resumida (sem atributos): relê pelo id.
    private UserRepresentation fullUser(String username) {
        String id = realm.admin().users().search(username, true).getFirst().getId();
        return realm.admin().users().get(id).toRepresentation();
    }

    private List<String> realmRoles(UserRepresentation user) {
        return realm.admin().users().get(user.getId()).roles().realmLevel().listEffective().stream()
                .map(RoleRepresentation::getName).toList();
    }

    // Espera o evento (envio assíncrono ao Graylog) e devolve o GELF dele.
    private String awaitGelf(String type) {
        String marker = "\"_type\":\"" + type + "\"";
        await().atMost(WAIT).until(() -> graylog.received().stream().anyMatch(gelf -> gelf.contains(marker)));
        return graylog.received().stream().filter(gelf -> gelf.contains(marker)).findFirst().orElseThrow();
    }
}
