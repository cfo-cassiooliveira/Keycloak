package com.workspace.keycloak.audit.gelf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.keycloak.util.JsonSerialization;

import com.workspace.keycloak.audit.AuditLevel;
import com.workspace.keycloak.audit.AuditRecord;

/**
 * Converte registros de auditoria em mensagens GELF 1.1 para o Graylog.
 *
 * <p>Cada campo do registro (já filtrado pela allowlist, sem dados pessoais) vira um campo adicional
 * {@code _campo}; os campos padrão do serviço (nome, versão, ambiente, host) são acrescentados.</p>
 */
public final class GelfMessageFactory {

    /** Níveis syslog usados pelo GELF. */
    static final int LEVEL_WARNING = 4;
    static final int LEVEL_INFORMATIONAL = 6;

    private static final BigDecimal MILLIS_PER_SECOND = BigDecimal.valueOf(1000);

    private final ServiceInfo serviceInfo;

    public GelfMessageFactory(ServiceInfo serviceInfo) {
        this.serviceInfo = serviceInfo;
    }

    public String toGelf(AuditRecord auditRecord) {
        Map<String, Object> gelf = new LinkedHashMap<>();
        gelf.put("version", "1.1");
        gelf.put("host", serviceInfo.host());
        gelf.put("short_message", shortMessage(auditRecord));
        gelf.put("timestamp", timestamp(auditRecord));
        gelf.put("level", auditRecord.level() == AuditLevel.WARN ? LEVEL_WARNING : LEVEL_INFORMATIONAL);
        gelf.put("_service_name", serviceInfo.serviceName());
        gelf.put("_service_version", serviceInfo.serviceVersion());
        gelf.put("_keycloak_version", serviceInfo.keycloakVersion());
        gelf.put("_environment", serviceInfo.environment());
        gelf.put("_log_source", "audit");
        // "_id" é reservado no GELF; o id do evento vai em "_event_id".
        auditRecord.fields().forEach((name, value) -> gelf.put("_" + name, value));
        try {
            return JsonSerialization.writeValueAsString(gelf);
        } catch (IOException e) {
            throw new UncheckedIOException("GELF message serialization failed", e);
        }
    }

    // Texto curto e legível na lista do Graylog: tipo/operação e o erro, sem nenhum dado pessoal.
    private static String shortMessage(AuditRecord auditRecord) {
        Map<String, Object> fields = auditRecord.fields();
        String action = fields.containsKey("operation")
                ? fields.get("operation") + " " + fields.getOrDefault("resource_type", "")
                : String.valueOf(fields.getOrDefault("type", "UNKNOWN"));
        String prefix = "Keycloak " + fields.getOrDefault("kind", "audit") + " event " + action.trim();
        return fields.containsKey("error") ? prefix + " failed: " + fields.get("error") : prefix;
    }

    // GELF exige segundos desde a época (UTC) com fração de milissegundos.
    private static BigDecimal timestamp(AuditRecord auditRecord) {
        Object occurredAt = auditRecord.fields().get("occurred_at");
        Instant instant = occurredAt == null ? Instant.now() : Instant.parse(occurredAt.toString());
        return BigDecimal.valueOf(instant.toEpochMilli()).divide(MILLIS_PER_SECOND);
    }
}
