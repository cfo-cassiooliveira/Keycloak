package com.workspace.keycloak.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Servidor local que imita a API interna do Legado: HTTPS (certificado da {@link TestPki}) ou HTTP puro, com a resposta
 * configurável por teste e registro das requisições recebidas.
 */
public final class FakeLegadoServer implements AutoCloseable {

    /** Requisição recebida. */
    public record Received(String method, String pathAndQuery, String signatureHeader, String contentType, String body) {
    }

    /** Resposta fixa para uma requisição. */
    public record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile long delayMillis;
    private volatile String locationHeader;
    private volatile int chunkedBytes;
    private volatile Function<Received, Reply> responder;

    private FakeLegadoServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::handle);
        server.start();
    }

    /** HTTPS restrito às versões de TLS indicadas (ex.: só TLSv1.2, para provar que o cliente exige TLS 1.3). */
    public static FakeLegadoServer https(TestPki pki, String... protocols) throws IOException, GeneralSecurityException {
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        SSLContext context = pki.serverContext();
        https.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters params) {
                javax.net.ssl.SSLParameters parameters = context.getDefaultSSLParameters();
                parameters.setProtocols(protocols);
                params.setSSLParameters(parameters);
            }
        });
        return new FakeLegadoServer(https);
    }

    public static FakeLegadoServer https(TestPki pki) throws IOException, GeneralSecurityException {
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        SSLContext context = pki.serverContext();
        https.setHttpsConfigurator(new HttpsConfigurator(context));
        return new FakeLegadoServer(https);
    }

    public static FakeLegadoServer http() throws IOException {
        return new FakeLegadoServer(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Received> requests() {
        return requests;
    }

    public FakeLegadoServer respond(int newStatus, String newBody) {
        this.status = newStatus;
        this.responseBody = newBody;
        return this;
    }

    /** Resposta calculada por requisição (substitui {@link #respond}); útil quando vários logins usam o mesmo servidor. */
    public FakeLegadoServer responder(Function<Received, Reply> newResponder) {
        this.responder = newResponder;
        return this;
    }

    public FakeLegadoServer delay(long millis) {
        this.delayMillis = millis;
        return this;
    }

    /** Responde em chunked (sem Content-Length) com {@code bytes} bytes, para provar o limite de leitura. */
    public FakeLegadoServer chunked(int bytes) {
        this.chunkedBytes = bytes;
        return this;
    }

    public FakeLegadoServer redirectTo(String location) {
        this.locationHeader = location;
        return this;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Received received = new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("X-Legado-Signature"),
                exchange.getRequestHeaders().getFirst("Content-Type"), new String(body, StandardCharsets.UTF_8));
        requests.add(received);
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Function<Received, Reply> fn = responder;
        Reply reply = fn == null ? new Reply(status, responseBody) : fn.apply(received);
        int replyStatus = reply.status();
        byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (locationHeader != null) {
            exchange.getResponseHeaders().add("Location", locationHeader);
        }
        try {
            if (chunkedBytes > 0) {
                exchange.sendResponseHeaders(replyStatus, 0);
                byte[] block = new byte[8192];
                java.util.Arrays.fill(block, (byte) 'x');
                for (int sent = 0; sent < chunkedBytes; sent += block.length) {
                    exchange.getResponseBody().write(block);
                }
            } else if (replyStatus == 204) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(replyStatus, response.length);
                exchange.getResponseBody().write(response);
            }
        } catch (IOException e) {
            // O cliente pode ter desistido (timeout): nada a fazer.
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
