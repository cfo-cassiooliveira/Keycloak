package com.workspace.keycloak.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import org.keycloak.util.JsonSerialization;

/**
 * Cliente HTTPS da API interna do Legado, só com o JDK ({@code java.net.http}, {@code javax.crypto}) e o Jackson do
 * Keycloak.
 *
 * <p>Confia somente na CA do Legado (SSLContext próprio, sem o truststore do sistema), exige TLS 1.3, tem timeout
 * total único e <b>nenhum retry</b>, não segue redirecionamentos e assina cada chamada com um nonce novo. Qualquer erro,
 * timeout, status inesperado ou corpo fora do contrato vira {@code Unavailable} com um {@link FailureReason}.
 * O cliente não registra log: quem trata a falha audita (Art. VI.3). Login e senha só existem no corpo da requisição.</p>
 *
 * <p>Sem retry porque a chamada acontece dentro do login: repetir alongaria a espera e poderia contar tentativas a mais
 * no bloqueio de conta do Legado. Custo: uma falha transitória aparece para o usuário como credencial inválida.</p>
 */
public final class HttpLegacyUserClient implements LegacyUserClient {

    static final String VERIFY_PATH = "/api/v1/internal/credential-verifications";
    static final String LOOKUP_PATH = "/api/v1/internal/user-lookups";

    private static final String TLS_VERSION = "TLSv1.3";
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient httpClient;
    private final LegadoRequestSigner signer;
    private final LegacyClientSettings settings;
    private final String origin;

    HttpLegacyUserClient(LegacyClientSettings settings) {
        this(settings, Clock.systemUTC());
    }

    HttpLegacyUserClient(LegacyClientSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.signer = new LegadoRequestSigner(settings.keyId(), settings.signingKey(), clock);
        this.origin = settings.baseUrl().getScheme() + "://" + settings.baseUrl().getRawAuthority();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (!settings.allowInsecureHttp()) {
            SSLParameters parameters = new SSLParameters();
            parameters.setProtocols(new String[] {TLS_VERSION});
            builder.sslContext(trustOnly(settings.caFile())).sslParameters(parameters);
        }
        this.httpClient = builder.build();
    }

    @Override
    public VerifyOutcome verifyCredentials(String login, String password) {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(password, "password");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("login", login);
        body.put("password", password);
        return call("POST", VERIFY_PATH, body, 200, LegacyResponseParser::parseVerify, VerifyOutcome.Unavailable::new);
    }

    @Override
    public LookupOutcome lookupUser(String login) {
        Objects.requireNonNull(login, "login");
        return call("POST", LOOKUP_PATH, Map.of("login", login), 200, LegacyResponseParser::parseLookup,
                LookupOutcome.Unavailable::new);
    }

    @Override
    public MarkOutcome markMigrated(UUID legacyUserId, UUID keycloakUserId) {
        Objects.requireNonNull(legacyUserId, "legacyUserId");
        Objects.requireNonNull(keycloakUserId, "keycloakUserId");
        String path = "/api/v1/internal/users/" + legacyUserId + "/migration";
        return call("PUT", path, Map.of("keycloakUserId", keycloakUserId.toString()), 204,
                (status, body) -> status == 204 ? new MarkOutcome.Marked() : new MarkOutcome.Conflict(),
                MarkOutcome.Unavailable::new, 409);
    }

    private <T> T call(String method, String path, Map<String, Object> payload, int successStatus,
            LegacyResponseParser.Parser<T> parser, Function<FailureReason, T> unavailable, int... extraStatuses) {
        try {
            byte[] body = JsonSerialization.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(origin + path))
                    .timeout(settings.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(LegadoRequestSigner.HEADER_NAME, signer.sign(method, path, body))
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = send(request);
            int status = response.statusCode();
            if (status == successStatus || contains(extraStatuses, status)) {
                return parser.parse(status, response.body());
            }
            return unavailable.apply(reasonFor(status));
        } catch (LegacyCallException e) {
            return unavailable.apply(e.reason());
        } catch (IOException | RuntimeException e) {
            // Mensagem da exceção ignorada de propósito: pode ecoar partes da requisição.
            return unavailable.apply(FailureReason.INVALID_RESPONSE);
        }
    }

    // Timeout total (conexão, envio e leitura do corpo) vale para a chamada inteira, sem retry.
    private HttpResponse<byte[]> send(HttpRequest request) throws LegacyCallException {
        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(request,
                info -> new LimitedBodySubscriber(info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        try {
            HttpResponse<byte[]> response = future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LegacyCallException(FailureReason.TIMEOUT);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LegacyCallException(FailureReason.CONNECTION);
        } catch (ExecutionException | CancellationException e) {
            throw new LegacyCallException(reasonFor(e.getCause() == null ? e : e.getCause()));
        }
    }

    private static FailureReason reasonFor(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof BodyTooLargeException) {
                return FailureReason.INVALID_RESPONSE;
            }
            if (current instanceof SSLException) {
                return FailureReason.TLS;
            }
            if (current instanceof HttpTimeoutException) {
                return FailureReason.TIMEOUT;
            }
        }
        return FailureReason.CONNECTION;
    }

    private static FailureReason reasonFor(int status) {
        if (status == 401) {
            return FailureReason.HTTP_401;
        }
        return status >= 500 ? FailureReason.HTTP_5XX : FailureReason.HTTP_ERROR;
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    // Só os certificados do arquivo entram no truststore: o do sistema nunca é consultado.
    private static SSLContext trustOnly(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(in);
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException("ca-file has no certificate");
            }
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                trustStore.setCertificateEntry("legado-ca-" + index++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            SSLContext context = SSLContext.getInstance(TLS_VERSION);
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("ca-file could not be loaded as a PEM/X.509 certificate", e);
        }
    }

    /** Corpo da resposta acima do limite. */
    private static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("response body exceeds the limit", null);
        }
    }

    /**
     * Acumula o corpo só até {@link #MAX_RESPONSE_BYTES}: ao passar do limite (declarado em Content-Length ou lido)
     * cancela a assinatura e falha, sem nunca guardar o corpo inteiro em memória.
     */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private final long declaredLength;
        private java.util.concurrent.Flow.Subscription subscription;

        LimitedBodySubscriber(long declaredLength) {
            this.declaredLength = declaredLength;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription newSubscription) {
            this.subscription = newSubscription;
            if (declaredLength > MAX_RESPONSE_BYTES) {
                fail();
                return;
            }
            newSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + item.remaining() > MAX_RESPONSE_BYTES) {
                    fail();
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }

        private void fail() {
            subscription.cancel();
            result.completeExceptionally(new BodyTooLargeException());
        }
    }

    /** Falha de transporte com motivo já classificado. */
    private static final class LegacyCallException extends Exception {
        private static final long serialVersionUID = 1L;
        private final FailureReason reason;

        LegacyCallException(FailureReason reason) {
            super(reason.code(), null, false, false);
            this.reason = reason;
        }

        FailureReason reason() {
            return reason;
        }
    }

    @Override
    public String toString() {
        return "HttpLegacyUserClient[" + settings + "]";
    }
}

