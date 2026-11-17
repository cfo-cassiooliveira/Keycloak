package com.workspace.keycloak.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Legado simulado compartilhado pela JVM de testes: sobe antes do Keycloak de teste, que recebe a porta e a CA pela
 * configuração da SPI ({@link LegacyServerConfig}). Fala HTTPS com uma PKI gerada em runtime: a SPI de produção não
 * aceita {@code http://}.
 */
public final class LegadoTestServer {

    public static final String KEY_ID = "keycloak-test";
    /** Chave HMAC fictícia de 256 bits, só de teste. */
    public static final String SIGNING_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static final TestPki PKI = createPki();
    private static final FakeLegadoServer INSTANCE = start();

    private LegadoTestServer() {
    }

    public static FakeLegadoServer instance() {
        return INSTANCE;
    }

    /** Arquivo PEM da CA do servidor de teste (valor de {@code ca-file}). */
    public static Path caFile() {
        return PKI.caPem();
    }

    private static TestPki createPki() {
        try {
            Path dir = Files.createTempDirectory("legado-test-pki");
            dir.toFile().deleteOnExit();
            return TestPki.create(dir, "legado");
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not create the test PKI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the test PKI", e);
        }
    }

    private static FakeLegadoServer start() {
        try {
            return FakeLegadoServer.https(PKI);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not start the HTTPS Legado simulator", e);
        }
    }
}
