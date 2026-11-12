package com.workspace.keycloak.audit;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

/**
 * Escreve registros de auditoria como uma linha JSON na categoria de log {@value #CATEGORY}.
 * É o destino usado quando o Graylog não está configurado.
 */
public final class ConsoleAuditSink implements AuditSink {

    public static final String CATEGORY = "workspace.audit";

    private final Logger logger;

    public ConsoleAuditSink(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void write(AuditRecord auditRecord) {
        String json = toJson(auditRecord);
        switch (auditRecord.level()) {
            case INFO -> logger.info(json);
            case WARN -> logger.warn(json);
        }
    }

    private static String toJson(AuditRecord auditRecord) {
        try {
            return JsonSerialization.writeValueAsString(auditRecord.fields());
        } catch (IOException e) {
            throw new UncheckedIOException("Audit record serialization failed", e);
        }
    }
}
