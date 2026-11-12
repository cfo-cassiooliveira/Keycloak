package com.workspace.keycloak.audit;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

/** Escreve registros de auditoria como uma linha JSON na categoria de log {@value #CATEGORY}. */
public final class AuditLogWriter {

    public static final String CATEGORY = "workspace.audit";

    private final Logger logger;

    public AuditLogWriter(Logger logger) {
        this.logger = logger;
    }

    public void write(AuditRecord auditRecord) {
        String json = toJson(auditRecord);
        switch (auditRecord.level()) {
            case INFO -> logger.info(json);
            case WARN -> logger.warn(json);
        }
    }

    /** Registra falha do próprio listener sem nenhum dado do evento (RN-03). */
    public void writeFailure(RuntimeException failure) {
        logger.error("Failed to write audit record", failure);
    }

    private static String toJson(AuditRecord auditRecord) {
        try {
            return JsonSerialization.writeValueAsString(auditRecord.fields());
        } catch (IOException e) {
            throw new UncheckedIOException("Audit record serialization failed", e);
        }
    }
}
