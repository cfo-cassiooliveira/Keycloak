package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.workspace.keycloak.support.FakeLegadoServer;
import com.workspace.keycloak.support.FakeLegadoServer.Received;
import com.workspace.keycloak.support.TestPki;

class HttpLegacyUserClientTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String PASSWORD = "S3nh@-Secreta-Do-Teste";
    private static final String LOGIN = "ana.souza@gmail.com";
    private static final UUID LEGACY_ID = UUID.fromString("0195f1d2-7c3a-7b11-9a00-000000000001");
    private static final UUID KEYCLOAK_ID = UUID.fromString("7d3b5f0e-1a2b-4c3d-8e9f-000000000002");
    private static final String USER_JSON = """
            {"id":"0195f1d2-7c3a-7b11-9a00-000000000001","userName":"ana.souza","email":"ana.souza@gmail.com",
             "emailConfirmed":true,"fullName":"Ana Souza","mobilePhone":"11987654321",
             "address":{"postalCode":"01001000","street":"Praça da Sé","number":"1","complement":null,
                        "district":"Sé","city":"São Paulo","cityIbgeCode":3550308,"state":"SP"},
             "roles":["user"]}""";
    private static final Pattern HEADER = Pattern.compile("^keyId=keycloak-1, t=(\\d+), n=([A-Za-z0-9_-]{22}), v1=(.+)$");

    @TempDir
    static Path tempDir;

    private static TestPki pki;
    private static TestPki otherPki;

    private FakeLegadoServer server;
    private HttpLegacyUserClient client;

    @BeforeAll
    static void createPki() throws Exception {
        pki = TestPki.create(tempDir, "legado");
        otherPki = TestPki.create(tempDir, "other");
    }

    @BeforeEach
    void startServer() throws Exception {
        server = FakeLegadoServer.https(pki);
        client = clientFor(server, Duration.ofSeconds(2), pki.caPem());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private static HttpLegacyUserClient clientFor(FakeLegadoServer server, Duration timeout, Path ca) {
        return new HttpLegacyUserClient(new LegacyClientSettings(URI.create("https://localhost:" + server.port()), timeout,
                "keycloak-1", KEY, ca, false));
    }

    // ---- verifyCredentials ----

    @Test
    void verifyCredentials_WhenValid_ReturnsTheUser() {
        server.respond(200, "{\"outcome\":\"valid\",\"user\":" + USER_JSON + "}");

        VerifyOutcome outcome = client.verifyCredentials(LOGIN, PASSWORD);

        assertThat(outcome).isInstanceOfSatisfying(VerifyOutcome.Valid.class, valid -> {
            assertThat(valid.user().id()).isEqualTo(LEGACY_ID);
            assertThat(valid.user().userName()).isEqualTo("ana.souza");
            assertThat(valid.user().email()).isEqualTo(LOGIN);
            assertThat(valid.user().emailConfirmed()).isTrue();
            assertThat(valid.user().fullName()).isEqualTo("Ana Souza");
            assertThat(valid.user().mobilePhone()).isEqualTo("11987654321");
            assertThat(valid.user().address().city()).isEqualTo("São Paulo");
            assertThat(valid.user().address().cityIbgeCode()).isEqualTo(3550308);
            assertThat(valid.user().roles()).containsExactly("user");
        });
        Received request = server.requests().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.pathAndQuery()).isEqualTo("/api/v1/internal/credential-verifications");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.body()).contains("\"login\":\"" + LOGIN + "\"").contains("\"password\":\"" + PASSWORD + "\"");
    }

    @ParameterizedTest
    @CsvSource({"not_found,NotFound", "invalid_password,InvalidPassword", "locked,Locked", "migrated,Migrated"})
    void verifyCredentials_WhenNotValid_ReturnsTheTypedOutcome(String outcome, String type) {
        server.respond(200, "{\"outcome\":\"" + outcome + "\"}");

        assertThat(client.verifyCredentials(LOGIN, PASSWORD).getClass().getSimpleName()).isEqualTo(type);
    }

    @Test
    void verifyCredentials_WhenUserIsExplicitlyNullOnNotFound_Accepts() {
        server.respond(200, "{\"outcome\":\"not_found\",\"user\":null}");

        assertThat(client.verifyCredentials(LOGIN, PASSWORD)).isInstanceOf(VerifyOutcome.NotFound.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[]",
            "{}",
            "{\"outcome\":42}",
            "{\"outcome\":\"surprise\"}",
            "{\"outcome\":\"valid\"}",
            "{\"outcome\":\"valid\",\"user\":null}",
            "{\"outcome\":\"valid\",\"user\":{\"id\":\"not-a-uuid\",\"userName\":\"a\",\"email\":\"a@b.c\"}}",
            "{\"outcome\":\"valid\",\"user\":{\"id\":\"0195f1d2-7c3a-7b11-9a00-000000000001\",\"email\":\"a@b.c\"}}",
            "{\"outcome\":\"valid\",\"user\":{\"id\":\"0195f1d2-7c3a-7b11-9a00-000000000001\",\"userName\":\"a\","
                    + "\"email\":\"a@b.c\",\"roles\":\"admin\"}}",
            "{\"outcome\":\"locked\",\"user\":{\"id\":\"0195f1d2-7c3a-7b11-9a00-000000000001\"}}",
            "{\"outcome\":\"not_found\",\"user\":{}}"})
    void verifyCredentials_WhenBodyBreaksTheContract_IsUnavailable(String body) {
        server.respond(200, body);

        assertThat(client.verifyCredentials(LOGIN, PASSWORD))
                .isEqualTo(new VerifyOutcome.Unavailable(FailureReason.INVALID_RESPONSE));
    }

    @ParameterizedTest
    @CsvSource({"500,HTTP_5XX", "503,HTTP_5XX", "401,HTTP_401", "400,HTTP_ERROR", "403,HTTP_ERROR", "404,HTTP_ERROR",
            "204,HTTP_ERROR"})
    void verifyCredentials_WhenStatusIsNot200_IsUnavailableWithTheMatchingReason(int status, FailureReason reason) {
        server.respond(status, "{\"outcome\":\"valid\",\"user\":" + USER_JSON + "}");

        assertThat(client.verifyCredentials(LOGIN, PASSWORD)).isEqualTo(new VerifyOutcome.Unavailable(reason));
    }

    @Test
    void verifyCredentials_WhenRedirected_DoesNotFollowAndIsUnavailable() {
        server.respond(302, "").redirectTo("https://localhost:" + server.port() + "/elsewhere");

        assertThat(client.verifyCredentials(LOGIN, PASSWORD))
                .isEqualTo(new VerifyOutcome.Unavailable(FailureReason.HTTP_ERROR));
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void verifyCredentials_WhenResponseIsHuge_IsUnavailable() {
        server.respond(200, "{\"outcome\":\"not_found\",\"pad\":\"" + "x".repeat(70_000) + "\"}");

        assertThat(client.verifyCredentials(LOGIN, PASSWORD))
                .isEqualTo(new VerifyOutcome.Unavailable(FailureReason.INVALID_RESPONSE));
    }

    // Sem Content-Length (chunked): o corpo de 1 MiB nunca é lido inteiro, a chamada falha como resposta inválida.
    @Test
    void verifyCredentials_WhenChunkedBodyExceedsTheLimit_IsUnavailableAsInvalidResponse() {
        server.respond(200, "").chunked(1024 * 1024);

        assertThat(client.verifyCredentials(LOGIN, PASSWORD))
                .isEqualTo(new VerifyOutcome.Unavailable(FailureReason.INVALID_RESPONSE));
    }

    @Test
    void limitedBodySubscriber_WhenBodyCrossesTheLimit_CancelsAndStopsAccumulating() throws Exception {
        HttpLegacyUserClient.LimitedBodySubscriber subscriber = new HttpLegacyUserClient.LimitedBodySubscriber(-1);
        boolean[] cancelled = {false};
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override
            public void request(long n) {
                // Demanda ilimitada: nada a fazer no duplo.
            }

            @Override
            public void cancel() {
                cancelled[0] = true;
            }
        });
        java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(HttpLegacyUserClient.MAX_RESPONSE_BYTES);

        subscriber.onNext(java.util.List.of(chunk));
        assertThat(cancelled[0]).isFalse();
        subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.allocate(1)));

        assertThat(cancelled[0]).isTrue();
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    @Test
    void limitedBodySubscriber_WhenContentLengthExceedsTheLimit_CancelsBeforeReadingAnything() {
        HttpLegacyUserClient.LimitedBodySubscriber subscriber =
                new HttpLegacyUserClient.LimitedBodySubscriber(HttpLegacyUserClient.MAX_RESPONSE_BYTES + 1L);
        boolean[] requested = {false};
        boolean[] cancelled = {false};
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            @Override
            public void request(long n) {
                requested[0] = true;
            }

            @Override
            public void cancel() {
                cancelled[0] = true;
            }
        });

        assertThat(requested[0]).isFalse();
        assertThat(cancelled[0]).isTrue();
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    // ---- timeout, sem retry ----

    @Test
    void verifyCredentials_WhenLegadoIsSlowerThanTwoSeconds_TimesOutWithoutRetry() {
        server.respond(200, "{\"outcome\":\"not_found\"}").delay(3_500);
        HttpLegacyUserClient defaultTimeoutClient = new HttpLegacyUserClient(new LegacyClientSettings(
                URI.create("https://localhost:" + server.port()),
                Duration.ofMillis(LegacyClientSettings.DEFAULT_TIMEOUT_MS), "keycloak-1", KEY, pki.caPem(), false));

        long start = System.nanoTime();
        VerifyOutcome outcome = defaultTimeoutClient.verifyCredentials(LOGIN, PASSWORD);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(outcome).isEqualTo(new VerifyOutcome.Unavailable(FailureReason.TIMEOUT));
        assertThat(elapsedMillis).isBetween(1_800L, 3_200L);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void verifyCredentials_WhenServerIsDown_IsUnavailableAsConnection() {
        int port = server.port();
        server.close();

        HttpLegacyUserClient downClient = new HttpLegacyUserClient(new LegacyClientSettings(
                URI.create("https://localhost:" + port), Duration.ofSeconds(2), "keycloak-1", KEY, pki.caPem(), false));

        assertThat(downClient.verifyCredentials(LOGIN, PASSWORD))
                .isEqualTo(new VerifyOutcome.Unavailable(FailureReason.CONNECTION));
    }

    // ---- TLS: só a CA do Legado ----

    @Test
    void verifyCredentials_WhenServerCertificateIsFromAnotherCa_IsUnavailableAsTlsAndSendsNothing() throws Exception {
        try (FakeLegadoServer foreign = FakeLegadoServer.https(otherPki)) {
            foreign.respond(200, "{\"outcome\":\"valid\",\"user\":" + USER_JSON + "}");

            VerifyOutcome outcome = clientFor(foreign, Duration.ofSeconds(2), pki.caPem())
                    .verifyCredentials(LOGIN, PASSWORD);

            assertThat(outcome).isEqualTo(new VerifyOutcome.Unavailable(FailureReason.TLS));
            assertThat(foreign.requests()).isEmpty();
        }
    }

    // O cliente exige TLS 1.3: servidor que só fala TLS 1.2 não recebe nenhuma requisição.
    @Test
    void verifyCredentials_WhenServerOnlySpeaksTls12_IsUnavailableAsTlsAndSendsNothing() throws Exception {
        try (FakeLegadoServer legacyTls = FakeLegadoServer.https(pki, "TLSv1.2")) {
            legacyTls.respond(200, "{\"outcome\":\"locked\"}");

            VerifyOutcome outcome = clientFor(legacyTls, Duration.ofSeconds(2), pki.caPem())
                    .verifyCredentials(LOGIN, PASSWORD);

            assertThat(outcome).isEqualTo(new VerifyOutcome.Unavailable(FailureReason.TLS));
            assertThat(legacyTls.requests()).isEmpty();
        }
    }

    @Test
    void constructor_WhenCaFileIsMissingOrNotACertificate_FailsAtStartup() throws IOException {
        Path garbage = Files.writeString(tempDir.resolve("garbage.pem"), "not a certificate");
        LegacyClientSettings missing = new LegacyClientSettings(URI.create("https://legado-api:8443"),
                Duration.ofSeconds(2), "keycloak-1", KEY, tempDir.resolve("absent.pem"), false);
        LegacyClientSettings invalid = new LegacyClientSettings(URI.create("https://legado-api:8443"),
                Duration.ofSeconds(2), "keycloak-1", KEY, garbage, false);

        assertThatThrownBy(() -> new HttpLegacyUserClient(missing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpLegacyUserClient(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settings_WhenUrlIsPlainHttp_IsRefusedBeforeAnyClientExists() {
        assertThatThrownBy(() -> new LegacyClientSettings(URI.create("http://localhost:" + server.port()),
                Duration.ofSeconds(2), "keycloak-1", KEY, pki.caPem(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyCredentials_WhenHttpIsExplicitlyAllowedForTests_WorksOverPlainHttp() throws Exception {
        try (FakeLegadoServer plain = FakeLegadoServer.http()) {
            plain.respond(200, "{\"outcome\":\"locked\"}");
            HttpLegacyUserClient plainClient = new HttpLegacyUserClient(new LegacyClientSettings(
                    URI.create("http://localhost:" + plain.port()), Duration.ofSeconds(2), "keycloak-1", KEY, null, true));

            assertThat(plainClient.verifyCredentials(LOGIN, PASSWORD)).isInstanceOf(VerifyOutcome.Locked.class);
        }
    }

    // ---- lookupUser ----

    @Test
    void lookupUser_WhenFound_ReturnsUserAndSendsOnlyTheLoginInTheBody() {
        server.respond(200, "{\"outcome\":\"found\",\"user\":" + USER_JSON + "}");

        LookupOutcome outcome = client.lookupUser(LOGIN);

        assertThat(outcome).isInstanceOfSatisfying(LookupOutcome.Found.class,
                found -> assertThat(found.user().id()).isEqualTo(LEGACY_ID));
        Received request = server.requests().get(0);
        assertThat(request.pathAndQuery()).isEqualTo("/api/v1/internal/user-lookups");
        assertThat(request.pathAndQuery()).doesNotContain("ana");
        assertThat(request.body()).isEqualTo("{\"login\":\"" + LOGIN + "\"}");
    }

    @ParameterizedTest
    @CsvSource({"not_found,NotFound", "migrated,Migrated"})
    void lookupUser_WhenNoUser_ReturnsTheTypedOutcome(String outcome, String type) {
        server.respond(200, "{\"outcome\":\"" + outcome + "\"}");

        assertThat(client.lookupUser(LOGIN).getClass().getSimpleName()).isEqualTo(type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"outcome\":\"found\"}", "{\"outcome\":\"valid\",\"user\":null}",
            "{\"outcome\":\"migrated\",\"user\":{\"id\":\"x\"}}", "garbage"})
    void lookupUser_WhenBodyBreaksTheContract_IsUnavailable(String body) {
        server.respond(200, body);

        assertThat(client.lookupUser(LOGIN)).isEqualTo(new LookupOutcome.Unavailable(FailureReason.INVALID_RESPONSE));
    }

    @Test
    void lookupUser_WhenServerFails_IsUnavailable() {
        server.respond(500, "{}");

        assertThat(client.lookupUser(LOGIN)).isEqualTo(new LookupOutcome.Unavailable(FailureReason.HTTP_5XX));
    }

    // ---- markMigrated ----

    @Test
    void markMigrated_When204_ReturnsMarkedWithPutOnTheUserRoute() {
        server.respond(204, "");

        assertThat(client.markMigrated(LEGACY_ID, KEYCLOAK_ID)).isInstanceOf(MarkOutcome.Marked.class);
        Received request = server.requests().get(0);
        assertThat(request.method()).isEqualTo("PUT");
        assertThat(request.pathAndQuery()).isEqualTo("/api/v1/internal/users/" + LEGACY_ID + "/migration");
        assertThat(request.body()).isEqualTo("{\"keycloakUserId\":\"" + KEYCLOAK_ID + "\"}");
    }

    @Test
    void markMigrated_When409_ReturnsConflict() {
        server.respond(409, "{\"title\":\"conflict\"}");

        assertThat(client.markMigrated(LEGACY_ID, KEYCLOAK_ID)).isInstanceOf(MarkOutcome.Conflict.class);
    }

    @ParameterizedTest
    @CsvSource({"200,HTTP_ERROR", "401,HTTP_401", "404,HTTP_ERROR", "500,HTTP_5XX"})
    void markMigrated_WhenAnyOtherStatus_IsUnavailable(int status, FailureReason reason) {
        server.respond(status, "{}");

        assertThat(client.markMigrated(LEGACY_ID, KEYCLOAK_ID)).isEqualTo(new MarkOutcome.Unavailable(reason));
    }

    @Test
    void markMigrated_WhenSlow_TimesOut() {
        server.respond(204, "").delay(1_500);
        HttpLegacyUserClient fast = clientFor(server, Duration.ofMillis(300), pki.caPem());

        assertThat(fast.markMigrated(LEGACY_ID, KEYCLOAK_ID)).isEqualTo(new MarkOutcome.Unavailable(FailureReason.TIMEOUT));
    }

    // ---- assinatura ----

    @Test
    void everyCall_IsSignedExactlyLikeTheLegadoExpectsWithANewNoncePerCall() {
        server.respond(200, "{\"outcome\":\"not_found\"}");
        LegadoRequestSigner reference = new LegadoRequestSigner("keycloak-1", KEY, Clock.systemUTC());

        client.verifyCredentials(LOGIN, PASSWORD);
        client.verifyCredentials(LOGIN, PASSWORD);
        client.lookupUser(LOGIN);

        assertThat(server.requests()).hasSize(3);
        assertThat(server.requests().stream().map(Received::signatureHeader).distinct()).hasSize(3);
        assertThat(server.requests().stream().map(r -> nonce(r.signatureHeader())).distinct()).hasSize(3);
        for (Received request : server.requests()) {
            Matcher matcher = HEADER.matcher(request.signatureHeader());
            assertThat(matcher.matches()).isTrue();
            assertThat(request.signatureHeader()).isEqualTo(reference.sign(Long.parseLong(matcher.group(1)),
                    matcher.group(2), request.method(), request.pathAndQuery(),
                    request.body().getBytes(StandardCharsets.UTF_8)));
            assertThat(Math.abs(Long.parseLong(matcher.group(1)) - System.currentTimeMillis() / 1000)).isLessThan(30);
        }
    }

    @Test
    void signature_NeverCarriesThePasswordOrTheKey() {
        server.respond(200, "{\"outcome\":\"not_found\"}");

        client.verifyCredentials(LOGIN, PASSWORD);

        String header = server.requests().get(0).signatureHeader();
        assertThat(header).doesNotContain(PASSWORD).doesNotContain(LOGIN)
                .doesNotContain(Base64.getEncoder().encodeToString(KEY));
    }

    // ---- sem vazamento ----

    @Test
    void toString_OfClientSettingsAndUser_NeverContainsSecretsOrPersonalData() {
        LegacyUser user = new LegacyUser(LEGACY_ID, "ana.souza", LOGIN, true, "Ana Souza", "11987654321",
                new LegacyAddress("01001000", "Praça da Sé", "1", null, "Sé", "São Paulo", 3550308, "SP"),
                java.util.List.of("user"));

        String text = client + " " + user + " " + user.address() + " " + new VerifyOutcome.Valid(user);

        assertThat(text).doesNotContain(PASSWORD).doesNotContain(LOGIN).doesNotContain("Ana Souza")
                .doesNotContain("11987654321").doesNotContain("Praça").doesNotContain("0123456789abcdef")
                .doesNotContain(Base64.getEncoder().encodeToString(KEY)).contains(LEGACY_ID.toString());
    }

    @Test
    void failures_NeverEchoLoginOrPasswordInAnyResult() {
        server.respond(500, "{\"detail\":\"" + LOGIN + " " + PASSWORD + "\"}");

        String text = client.verifyCredentials(LOGIN, PASSWORD) + " " + client.lookupUser(LOGIN);

        assertThat(text).doesNotContain(PASSWORD).doesNotContain(LOGIN);
    }

    private static String nonce(String header) {
        Matcher matcher = HEADER.matcher(header);
        assertThat(matcher.matches()).isTrue();
        return matcher.group(2);
    }
}
