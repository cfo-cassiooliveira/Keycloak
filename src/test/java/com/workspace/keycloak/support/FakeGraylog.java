package com.workspace.keycloak.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * Input GELF HTTP falso para os testes de integração: guarda o que recebe e responde com o status configurado
 * (202 aceita, 503 simula o Graylog indisponível). Sobe uma vez por JVM, antes do Keycloak de teste.
 */
public final class FakeGraylog {

    private static final FakeGraylog INSTANCE = start();

    private final HttpServer server;
    private final Path fallbackFile;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(202);

    private FakeGraylog(HttpServer server, Path fallbackFile) {
        this.server = server;
        this.fallbackFile = fallbackFile;
    }

    public static FakeGraylog instance() {
        return INSTANCE;
    }

    public String gelfUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/gelf";
    }

    public Path fallbackFile() {
        return fallbackFile;
    }

    public List<String> received() {
        return received;
    }

    public void accept() {
        status.set(202);
    }

    public void reject() {
        status.set(503);
    }

    public void reset() throws IOException {
        received.clear();
        accept();
        Files.deleteIfExists(fallbackFile);
    }

    private static FakeGraylog start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Path fallbackFile = Files.createTempDirectory("kc-audit-fallback").resolve("audit-fallback.log");
            FakeGraylog fake = new FakeGraylog(server, fallbackFile);
            server.createContext("/gelf", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int responseStatus = fake.status.get();
                if (responseStatus / 100 == 2) {
                    fake.received.add(body);
                }
                exchange.sendResponseHeaders(responseStatus, -1);
                exchange.close();
            });
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start fake Graylog", e);
        }
    }
}
