package com.workspace.keycloak.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Servidor SMTP falso para os testes de integração: aceita qualquer remetente e destinatário e guarda o conteúdo das
 * mensagens (cabeçalhos e corpo). Só o necessário para o Keycloak de teste enviar o e-mail de reset de senha.
 */
public final class FakeSmtp implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> messages = new CopyOnWriteArrayList<>();

    private FakeSmtp(ServerSocket server) {
        this.server = server;
    }

    /** Sobe o servidor numa porta livre de localhost. */
    public static FakeSmtp start() {
        try {
            FakeSmtp smtp = new FakeSmtp(new ServerSocket(0, 10, InetAddress.getLoopbackAddress()));
            Thread thread = new Thread(smtp::acceptLoop, "fake-smtp");
            thread.setDaemon(true);
            thread.start();
            return smtp;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                converse(socket);
            } catch (IOException e) {
                // Servidor fechado no fim do teste ou cliente desconectado: só encerra a conversa atual.
            }
        }
    }

    private void converse(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        reply(out, "220 fake-smtp");
        String line;
        while ((line = in.readLine()) != null) {
            String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line.toUpperCase();
            switch (command) {
                case "DATA" -> {
                    reply(out, "354 end with <CRLF>.<CRLF>");
                    messages.add(readData(in));
                    reply(out, "250 queued");
                }
                case "QUIT" -> {
                    reply(out, "221 bye");
                    return;
                }
                default -> reply(out, "250 ok");
            }
        }
    }

    private static String readData(BufferedReader in) throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null && !".".equals(line)) {
            // Desfaz o "dot-stuffing" do SMTP (RFC 5321, 4.5.2): linha que começa com ponto chega com um ponto a mais.
            data.append(line.startsWith(".") ? line.substring(1) : line).append("\r\n");
        }
        return data.toString();
    }

    private static void reply(Writer out, String response) throws IOException {
        out.write(response + "\r\n");
        out.flush();
    }
}
