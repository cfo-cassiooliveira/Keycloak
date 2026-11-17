package com.workspace.keycloak.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    // Formato de MaskedIdentifier: até 3 caracteres + *** (e @ + primeiro rótulo do domínio), ou só ***.
    private static final Pattern MASKED_LOGIN_PATTERN = Pattern.compile("^(?:[^\\s@*]{1,3}\\*{3}(?:@[^\\s@.*]+)?|\\*{3})$");

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("^[a-z0-9_]{1,40}$");

    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:/@-]{1,255}$");

    private static final Set<String> ALLOWED_FLOWS = Set.of("login", "reset");

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

    /**
     * Converte um evento de migração. Só entram campos conhecidos e válidos: {@code masked_login} apenas nos tipos
     * do ADR-0007 e apenas no formato de máscara; ids só como UUID; {@code flow} e {@code error} só como códigos
     * curtos. Qualquer outro valor é descartado, para um login ou e-mail completo nunca sair por engano.
     */
    public AuditRecord map(MigrationAuditEntry entry) {
        return map(entry, Instant.now());
    }

    AuditRecord map(MigrationAuditEntry entry, Instant occurredAt) {
        Map<String, Object> fields = newFields("migration");
        put(fields, "event_id", UUID.randomUUID().toString());
        put(fields, "type", entry.type().name());
        put(fields, "client_id", matchingOrNull(CLIENT_ID_PATTERN, entry.clientId()));
        put(fields, "user_id", matchingOrNull(UUID_PATTERN, entry.userId()));
        put(fields, "legacy_user_id", matchingOrNull(UUID_PATTERN, entry.legacyUserId()));
        put(fields, "password_update_required", entry.passwordUpdateRequired());
        if (entry.type().allowsMaskedLogin()) {
            put(fields, "masked_login", matchingOrNull(MASKED_LOGIN_PATTERN, entry.maskedLogin()));
        }
        put(fields, "flow", entry.flow() != null && ALLOWED_FLOWS.contains(entry.flow()) ? entry.flow() : null);
        put(fields, "error", matchingOrNull(ERROR_CODE_PATTERN, entry.error()));
        put(fields, "occurred_at", occurredAt.toString());
        return new AuditRecord(entry.type().level(), fields);
    }

    private static String matchingOrNull(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches() ? value : null;
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
