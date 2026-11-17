package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.models.KeycloakSession;

import com.workspace.keycloak.audit.AuditEventMapper;
import com.workspace.keycloak.audit.AuditRecord;
import com.workspace.keycloak.audit.AuditSink;
import com.workspace.keycloak.audit.MigrationAuditEntry;
import com.workspace.keycloak.audit.MigrationAuditEvent;
import com.workspace.keycloak.audit.WorkspaceAuditEventListenerProvider;

class MigrationAuditPublisherTest {

    private static final String PROVIDER_ID = "workspace-audit";

    private final List<AuditRecord> written = new ArrayList<>();
    private final KeycloakSession session = mock(KeycloakSession.class);

    @Test
    void forSession_WhenAuditListenerExists_WritesToItsSink() {
        AuditSink sink = written::add;
        when(session.getProvider(EventListenerProvider.class, PROVIDER_ID)).thenReturn(
                new WorkspaceAuditEventListenerProvider(new AuditEventMapper(), sink, mock(Logger.class)));

        MigrationAuditPublisher.forSession(session).publish(
                MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_USER_NOT_FOUND).withMaskedLogin("ana***@gmail"));

        assertThat(written).singleElement().satisfies(auditRecord ->
                assertThat(auditRecord.fields()).containsEntry("type", "LEGACY_USER_NOT_FOUND")
                        .containsEntry("masked_login", "ana***@gmail"));
    }

    @Test
    void forSession_WhenListenerIsMissingOrLookupFails_DoesNotThrow() {
        when(session.getProvider(any(Class.class), anyString())).thenReturn(null);
        MigrationAuditPublisher missing = MigrationAuditPublisher.forSession(session);

        KeycloakSession broken = mock(KeycloakSession.class);
        when(broken.getProvider(any(Class.class), anyString())).thenThrow(new IllegalStateException("boom"));
        MigrationAuditPublisher failing = MigrationAuditPublisher.forSession(broken);

        MigrationAuditEntry entry = MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_UNAVAILABLE);
        assertThatCode(() -> {
            missing.publish(entry);
            failing.publish(entry);
        }).doesNotThrowAnyException();
    }

    @Test
    void publish_WhenWriterThrows_DoesNotPropagate() {
        MigrationAuditPublisher publisher = new MigrationAuditPublisher(entry -> {
            throw new IllegalStateException("sink down");
        });

        assertThatCode(() -> publisher.publish(MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_MIGRATION_MARKED)))
                .doesNotThrowAnyException();
    }
}
