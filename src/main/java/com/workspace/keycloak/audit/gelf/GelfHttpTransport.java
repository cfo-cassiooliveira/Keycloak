package com.workspace.keycloak.audit.gelf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Envia GELF pelo input HTTP do Graylog.
 *
 * <p>Diferente do GELF TCP/UDP, o HTTP devolve status: só um 2xx (o Graylog responde 202 Accepted) conta como
 * gravado. Assim o fallback é acionado exatamente quando a mensagem não chegou (Art. VII.1: timeout em toda
 * chamada remota).</p>
 */
public final class GelfHttpTransport implements GelfTransport {

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration timeout;

    public GelfHttpTransport(URI endpoint, Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), endpoint, timeout);
    }

    GelfHttpTransport(HttpClient httpClient, URI endpoint, Duration timeout) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public void send(String gelfMessage) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gelfMessage, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending GELF message", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Graylog rejected GELF message with HTTP status " + response.statusCode());
        }
    }

    @Override
    public String destination() {
        return endpoint.toString();
    }
}
