package com.workspace.keycloak.audit.gelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Usa um servidor HTTP real (JDK) no lugar do input GELF HTTP do Graylog. */
class GelfHttpTransportTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(202);
    private final AtomicInteger delayMs = new AtomicInteger(0);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void send_WhenGraylogAccepts_PostsTheGelfJson() throws Exception {
        GelfHttpTransport transport = new GelfHttpTransport(startServer(), TIMEOUT);

        transport.send("{\"version\":\"1.1\",\"short_message\":\"ação\"}");

        assertThat(received).containsExactly("{\"version\":\"1.1\",\"short_message\":\"ação\"}");
    }

    @Test
    void send_WhenGraylogRejects_ThrowsWithStatus() throws Exception {
        status.set(400);
        GelfHttpTransport transport = new GelfHttpTransport(startServer(), TIMEOUT);

        assertThatThrownBy(() -> transport.send("{}"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP status 400");
    }

    @Test
    void send_WhenGraylogIsDown_Throws() throws Exception {
        URI unreachable = URI.create("http://127.0.0.1:" + freePort() + "/gelf");
        GelfHttpTransport transport = new GelfHttpTransport(unreachable, TIMEOUT);

        assertThatThrownBy(() -> transport.send("{}")).isInstanceOf(IOException.class);
    }

    @Test
    void send_WhenGraylogIsSlowerThanTheTimeout_Throws() throws Exception {
        delayMs.set(2000);
        GelfHttpTransport transport = new GelfHttpTransport(startServer(), TIMEOUT);

        assertThatThrownBy(() -> transport.send("{}")).isInstanceOf(IOException.class);
    }

    @Test
    void send_WhenInterrupted_ThrowsAndKeepsTheInterruptFlag() throws Exception {
        GelfHttpTransport transport = new GelfHttpTransport(startServer(), TIMEOUT);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> transport.send("{}")).isInstanceOf(IOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void destination_IsTheEndpointUrl() {
        URI endpoint = URI.create("http://graylog:12202/gelf");

        assertThat(new GelfHttpTransport(endpoint, TIMEOUT).destination()).isEqualTo("http://graylog:12202/gelf");
    }

    private URI startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gelf", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sleep(delayMs.get());
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/gelf");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
