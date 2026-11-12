package com.workspace.keycloak.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registro de auditoria pronto para ser escrito no log.
 * Contém apenas campos da lista de permissão (RN-01) e nunca valores nulos.
 */
public record AuditRecord(AuditLevel level, Map<String, Object> fields) {

    public AuditRecord {
        Objects.requireNonNull(level, "level");
        // Cópia defensiva preservando a ordem para o JSON sair estável.
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
