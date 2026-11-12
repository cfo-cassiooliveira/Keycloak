package com.workspace.keycloak.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;

/**
 * Converte eventos do Keycloak em registros de auditoria sem dados pessoais.
 *
 * <p>Usa lista de permissão (allowlist): qualquer campo não listado aqui é descartado,
 * inclusive campos que versões futuras do Keycloak venham a adicionar (ADR-0002).
 * Username, e-mail, IP, id de sessão, representação e caminho de recurso nunca saem; do caminho, só o UUID
 * do usuário afetado (código técnico, LGPD).</p>
 */
public final class AuditEventMapper {

    /** Versão do contrato do log de auditoria; mudança incompatível exige incrementar (Art. IX.2). */
    public static final int AUDIT_SCHEMA_VERSION = 1;

    /** Detalhes de evento de usuário que não carregam dado pessoal. */
    static final Set<String> ALLOWED_DETAIL_KEYS = Set.of("auth_method", "grant_type");

    private static final String ERROR_SUFFIX = "_ERROR";

    // Só o UUID do usuário afetado sai do caminho do recurso; o restante do caminho pode conter nomes.
    private static final Pattern USER_RESOURCE_PATH = Pattern.compile(
            "^users/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:/.*)?$");

    public AuditRecord map(Event event) {
        Map<String, Object> fields = newFields("user");
        put(fields, "event_id", event.getId());
        put(fields, "type", event.getType().name());
        put(fields, "realm_id", event.getRealmId());
        put(fields, "realm_name", event.getRealmName());
        put(fields, "client_id", event.getClientId());
        put(fields, "user_id", event.getUserId());
        put(fields, "error", event.getError());
        if (event.getDetails() != null) {
            event.getDetails().entrySet().stream()
                    .filter(detail -> ALLOWED_DETAIL_KEYS.contains(detail.getKey()))
                    .forEach(detail -> put(fields, detail.getKey(), detail.getValue()));
        }
        put(fields, "occurred_at", toUtc(event.getTime()));

        AuditLevel level = event.getType().name().endsWith(ERROR_SUFFIX) ? AuditLevel.WARN : AuditLevel.INFO;
        return new AuditRecord(level, fields);
    }

    public AuditRecord map(AdminEvent event) {
        Map<String, Object> fields = newFields("admin");
        put(fields, "event_id", event.getId());
        put(fields, "operation", event.getOperationType().name());
        put(fields, "resource_type", event.getResourceTypeAsString());
        put(fields, "realm_id", event.getRealmId());
        put(fields, "realm_name", event.getRealmName());
        put(fields, "target_user_id", targetUserId(event.getResourcePath()));
        AuthDetails actor = event.getAuthDetails();
        if (actor != null) {
            put(fields, "actor_user_id", actor.getUserId());
            put(fields, "actor_client_id", actor.getClientId());
        }
        put(fields, "error", event.getError());
        put(fields, "occurred_at", toUtc(event.getTime()));

        AuditLevel level = event.getError() == null ? AuditLevel.INFO : AuditLevel.WARN;
        return new AuditRecord(level, fields);
    }

    private static Map<String, Object> newFields(String kind) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("audit_schema", AUDIT_SCHEMA_VERSION);
        fields.put("kind", kind);
        return fields;
    }

    // Campos nulos são omitidos para o registro conter só o que de fato aconteceu.
    private static void put(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    private static String targetUserId(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        Matcher matcher = USER_RESOURCE_PATH.matcher(resourcePath);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String toUtc(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }
}
