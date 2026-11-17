package com.workspace.keycloak.audit;

/**
 * Tipos de evento de auditoria da migração de usuários do Legado (spec 001 do cadastro, plan §6).
 *
 * <p>Não existem no Keycloak, então não passam pelo {@code EventBuilder}: a SPI os emite direto ao
 * {@link AuditSink}. Só os tipos com {@code allowsMaskedLogin} aceitam o campo {@code masked_login} (ADR-0007, item 1).</p>
 */
public enum MigrationAuditEvent {
    LEGACY_USER_MIGRATED(AuditLevel.INFO, false),
    LEGACY_USER_PREPARED_FOR_RESET(AuditLevel.INFO, false),
    LEGACY_MIGRATION_MARKED(AuditLevel.INFO, false),
    LEGACY_MIGRATION_MARK_FAILED(AuditLevel.ERROR, false),
    LEGACY_USER_NOT_FOUND(AuditLevel.WARN, true),
    LEGACY_PASSWORD_MISMATCH(AuditLevel.WARN, true),
    LEGACY_ACCOUNT_LOCKED(AuditLevel.WARN, true),
    LEGACY_ALREADY_MIGRATED(AuditLevel.WARN, true),
    RESET_UNKNOWN_USER(AuditLevel.WARN, true),
    LEGACY_UNAVAILABLE(AuditLevel.ERROR, true);

    private final AuditLevel level;
    private final boolean allowsMaskedLogin;

    MigrationAuditEvent(AuditLevel level, boolean allowsMaskedLogin) {
        this.level = level;
        this.allowsMaskedLogin = allowsMaskedLogin;
    }

    public AuditLevel level() {
        return level;
    }

    public boolean allowsMaskedLogin() {
        return allowsMaskedLogin;
    }
}
