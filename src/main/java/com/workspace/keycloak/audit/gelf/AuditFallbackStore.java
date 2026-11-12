package com.workspace.keycloak.audit.gelf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Arquivo local que guarda as mensagens GELF que não chegaram ao Graylog, uma por linha (JSON Lines).
 * Cada linha é a mensagem GELF original, pronta para ser reenviada ao input HTTP do Graylog.
 */
public final class AuditFallbackStore {

    private final Path file;

    public AuditFallbackStore(Path file) {
        this.file = file;
    }

    /** Acrescenta a mensagem ao fim do arquivo; sincronizado para linhas nunca se misturarem. */
    public synchronized void append(String gelfMessage) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, gelfMessage + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    public Path file() {
        return file;
    }
}
