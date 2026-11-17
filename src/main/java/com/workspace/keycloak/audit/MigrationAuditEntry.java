package com.workspace.keycloak.audit;

import java.util.Objects;

/**
 * Dados de um evento de migração, antes da filtragem pela allowlist do {@link AuditEventMapper}.
 *
 * <p>Campos opcionais podem ser nulos. {@code maskedLogin} deve vir de {@code MaskedIdentifier}; o mapper
 * ainda descarta qualquer valor que não tenha o formato de máscara.</p>
 *
 * @param type tipo do evento
 * @param userId id técnico do usuário no Keycloak
 * @param legacyUserId id do usuário no Legado
 * @param passwordUpdateRequired se o usuário precisa trocar a senha (só em LEGACY_USER_MIGRATED)
 * @param maskedLogin identificador já mascarado
 * @param flow {@code login} ou {@code reset}
 * @param error código curto do erro (ex.: {@code timeout}, {@code http_5xx})
 * @param clientId id técnico do client OIDC da autenticação (não é dado pessoal)
 */
public record MigrationAuditEntry(MigrationAuditEvent type, String userId, String legacyUserId,
        Boolean passwordUpdateRequired, String maskedLogin, String flow, String error, String clientId) {

    public MigrationAuditEntry {
        Objects.requireNonNull(type, "type");
    }

    /** Evento só com o tipo; use {@code with*} para os demais campos. */
    public static MigrationAuditEntry of(MigrationAuditEvent type) {
        return new MigrationAuditEntry(type, null, null, null, null, null, null, null);
    }

    public MigrationAuditEntry withUserId(String value) {
        return new MigrationAuditEntry(type, value, legacyUserId, passwordUpdateRequired, maskedLogin, flow, error,
                clientId);
    }

    public MigrationAuditEntry withLegacyUserId(String value) {
        return new MigrationAuditEntry(type, userId, value, passwordUpdateRequired, maskedLogin, flow, error,
                clientId);
    }

    public MigrationAuditEntry withPasswordUpdateRequired(boolean value) {
        return new MigrationAuditEntry(type, userId, legacyUserId, value, maskedLogin, flow, error, clientId);
    }

    public MigrationAuditEntry withMaskedLogin(String value) {
        return new MigrationAuditEntry(type, userId, legacyUserId, passwordUpdateRequired, value, flow, error,
                clientId);
    }

    public MigrationAuditEntry withFlow(String value) {
        return new MigrationAuditEntry(type, userId, legacyUserId, passwordUpdateRequired, maskedLogin, value, error,
                clientId);
    }

    public MigrationAuditEntry withError(String value) {
        return new MigrationAuditEntry(type, userId, legacyUserId, passwordUpdateRequired, maskedLogin, flow, value,
                clientId);
    }

    public MigrationAuditEntry withClientId(String value) {
        return new MigrationAuditEntry(type, userId, legacyUserId, passwordUpdateRequired, maskedLogin, flow, error,
                value);
    }
}
