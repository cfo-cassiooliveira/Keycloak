package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.workspace.keycloak.audit.MigrationAuditEntry;
import com.workspace.keycloak.audit.MigrationAuditEvent;

class LegacyMigrationMarkerTest {

    private static final String LEGACY_ID = "0b2f4c1e-8a3d-4e5f-9a6b-7c8d9e0f1a2b";
    private static final String KC_ID = "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b";

    private final LegacyUserClient client = mock(LegacyUserClient.class);
    private final List<MigrationAuditEntry> events = new ArrayList<>();
    private final LegacyMigrationMarker marker =
            new LegacyMigrationMarker(client, new MigrationAuditPublisher(events::add));

    @Test
    void mark_WhenLegadoAccepts_AuditsMarkedWithIdsOnly() {
        when(client.markMigrated(UUID.fromString(LEGACY_ID), UUID.fromString(KC_ID)))
                .thenReturn(new MarkOutcome.Marked());

        marker.mark(LEGACY_ID, KC_ID);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_MIGRATION_MARKED);
            assertThat(event.userId()).isEqualTo(KC_ID);
            assertThat(event.legacyUserId()).isEqualTo(LEGACY_ID);
            assertThat(event.error()).isNull();
        });
    }

    @Test
    void mark_WhenLegadoReportsConflict_AuditsFailureWithoutThrowing() {
        when(client.markMigrated(any(), any())).thenReturn(new MarkOutcome.Conflict());

        marker.mark(LEGACY_ID, KC_ID);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED);
            assertThat(event.error()).isEqualTo("conflict");
        });
    }

    @Test
    void mark_WhenLegadoUnavailable_AuditsFailureWithReason() {
        when(client.markMigrated(any(), any())).thenReturn(new MarkOutcome.Unavailable(FailureReason.HTTP_5XX));

        marker.mark(LEGACY_ID, KC_ID);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED);
            assertThat(event.error()).isEqualTo("http_5xx");
        });
    }

    @Test
    void mark_WhenClientThrows_AuditsFailureWithoutThrowing() {
        when(client.markMigrated(any(), any())).thenThrow(new IllegalStateException("boom"));

        marker.mark(LEGACY_ID, KC_ID);

        assertThat(events).singleElement().satisfies(event -> assertThat(event.error()).isEqualTo("unexpected"));
    }

    @Test
    void mark_WhenIdIsNotAUuid_AuditsFailureWithoutCallingLegado() {
        marker.mark("not-a-uuid", KC_ID);

        verifyNoInteractions(client);
        assertThat(events).singleElement().satisfies(event -> assertThat(event.error()).isEqualTo("invalid_id"));
    }
}
