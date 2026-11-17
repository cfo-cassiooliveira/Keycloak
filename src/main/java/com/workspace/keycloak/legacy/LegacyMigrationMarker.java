package com.workspace.keycloak.legacy;

import java.util.Objects;
import java.util.UUID;

import org.jboss.logging.Logger;

import com.workspace.keycloak.audit.MigrationAuditEntry;
import com.workspace.keycloak.audit.MigrationAuditEvent;

/**
 * Marca o usuário do Legado como migrado e audita o resultado (RN-07). A chamada ao Legado é idempotente. Nunca lança
 * exceção: uma falha vira o evento LEGACY_MIGRATION_MARK_FAILED (Error), nunca interrompe o login nem o reset.
 */
final class LegacyMigrationMarker {

    private static final Logger LOG = Logger.getLogger(LegacyMigrationMarker.class);
    static final String ERROR_CONFLICT = "conflict";
    static final String ERROR_INVALID_ID = "invalid_id";
    static final String ERROR_UNEXPECTED = "unexpected";

    private final LegacyUserClient client;
    private final MigrationAuditPublisher audit;

    LegacyMigrationMarker(LegacyUserClient client, MigrationAuditPublisher audit) {
        this.client = Objects.requireNonNull(client, "client");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    void mark(String legacyUserId, String keycloakUserId) {
        try {
            UUID legacyId = UUID.fromString(legacyUserId);
            UUID keycloakId = UUID.fromString(keycloakUserId);
            switch (client.markMigrated(legacyId, keycloakId)) {
                case MarkOutcome.Marked ignored -> publish(MigrationAuditEvent.LEGACY_MIGRATION_MARKED,
                        keycloakUserId, legacyUserId, null);
                case MarkOutcome.Conflict ignored -> publish(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED,
                        keycloakUserId, legacyUserId, ERROR_CONFLICT);
                case MarkOutcome.Unavailable unavailable -> publish(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED,
                        keycloakUserId, legacyUserId, unavailable.reason().code());
            }
        } catch (IllegalArgumentException e) {
            LOG.error("Legacy migration mark skipped: user id is not a UUID");
            publish(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED, keycloakUserId, legacyUserId, ERROR_INVALID_ID);
        } catch (RuntimeException e) {
            LOG.errorf("Legacy migration mark failed: %s", e.getClass().getName());
            publish(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED, keycloakUserId, legacyUserId, ERROR_UNEXPECTED);
        }
    }

    private void publish(MigrationAuditEvent type, String keycloakUserId, String legacyUserId, String error) {
        MigrationAuditEntry entry = MigrationAuditEntry.of(type).withUserId(keycloakUserId)
                .withLegacyUserId(legacyUserId);
        audit.publish(error == null ? entry : entry.withError(error));
    }
}
