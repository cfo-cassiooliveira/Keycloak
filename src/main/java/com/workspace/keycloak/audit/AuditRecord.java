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

    /** Devolve uma cópia com o campo acrescentado; valor nulo devolve o próprio registro. */
    public AuditRecord withField(String name, Object value) {
        if (value == null) {
            return this;
        }
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.put(name, value);
        return new AuditRecord(level, copy);
    }
}
