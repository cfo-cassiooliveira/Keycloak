package com.workspace.keycloak.audit;

import java.util.function.Supplier;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;

/**
 * Listener de eventos que registra auditoria sem dados pessoais.
 * Nunca propaga exceção: uma falha de auditoria não pode interromper o login (RN-03).
 */
public final class WorkspaceAuditEventListenerProvider implements EventListenerProvider {

    private final AuditEventMapper mapper;
    private final AuditLogWriter writer;

    public WorkspaceAuditEventListenerProvider(AuditEventMapper mapper, AuditLogWriter writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    @Override
    public void onEvent(Event event) {
        writeSafely(() -> mapper.map(event));
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // A representação é ignorada mesmo quando o realm pede para incluí-la (RN-01).
        writeSafely(() -> mapper.map(event));
    }

    @Override
    public void close() {
        // Sem recursos por sessão para liberar.
    }

    private void writeSafely(Supplier<AuditRecord> auditRecord) {
        try {
            writer.write(auditRecord.get());
        } catch (RuntimeException e) {
            writer.writeFailure(e);
        }
    }
}
