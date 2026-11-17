package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.workspace.keycloak.audit.MigrationAuditEntry;
import com.workspace.keycloak.audit.MigrationAuditEvent;
import com.workspace.keycloak.legacy.LegacyMigrationService.ResetResult;
import com.workspace.keycloak.legacy.MigrationUserStore.DuplicateUserException;
import com.workspace.keycloak.legacy.MigrationUserStore.ExistingUser;
import com.workspace.keycloak.legacy.MigrationUserStore.InvalidProfileException;
import com.workspace.keycloak.legacy.MigrationUserStore.StoredUser;

/** RN-06: reset de senha com usuário que só existe no Legado. */
class LegacyMigrationServiceResetTest {

    private static final UUID LEGACY_ID = UUID.fromString("0b2f4c1e-8a3d-4e5f-9a6b-7c8d9e0f1a2b");
    private static final String KC_ID = "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final LegacyUser LEGACY_USER =
            new LegacyUser(LEGACY_ID, "ana.souza", "ana.souza@gmail.com", true, "Ana Souza", null, null, List.of());

    private final LegacyUserClient client = mock(LegacyUserClient.class);
    private final MigrationUserStore store = mock(MigrationUserStore.class);
    private final List<MigrationAuditEntry> events = new ArrayList<>();
    private final LegacyMigrationService service =
            new LegacyMigrationService(client, store, new MigrationAuditPublisher(events::add));

    @Test
    void prepareReset_WhenUserExistsInKeycloak_DoesNotCallLegado() {
        when(store.find("ana.souza")).thenReturn(Optional.of(new ExistingUser(KC_ID, false, LEGACY_ID.toString())));

        assertThat(service.prepareReset(" Ana.Souza ")).isEqualTo(ResetResult.EXISTING);

        verifyNoInteractions(client);
        assertThat(events).isEmpty();
    }

    @Test
    void prepareReset_WhenLoginIsBlank_DoesNothing() {
        assertThat(service.prepareReset("  ")).isEqualTo(ResetResult.EXISTING);

        verifyNoInteractions(client, store);
    }

    @Test
    void prepareReset_WhenOnlyInLegado_CreatesUserWithoutPasswordAndAudits() {
        when(store.find("ana.souza@gmail.com")).thenReturn(Optional.empty());
        when(client.lookupUser("ana.souza@gmail.com")).thenReturn(new LookupOutcome.Found(LEGACY_USER));
        when(store.createWithoutPassword(LEGACY_USER)).thenReturn(new StoredUser(KC_ID, false));

        assertThat(service.prepareReset("Ana.Souza@gmail.com")).isEqualTo(ResetResult.PREPARED);

        verify(store, never()).createOrComplete(any(), any(), any());
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_USER_PREPARED_FOR_RESET);
            assertThat(event.userId()).isEqualTo(KC_ID);
            assertThat(event.legacyUserId()).isEqualTo(LEGACY_ID.toString());
            assertThat(event.maskedLogin()).isNull();
        });
    }

    @Test
    void prepareReset_WhenNotFound_AuditsResetUnknownUserWithMaskedLogin() {
        when(store.find("ana.silva@gmail.com")).thenReturn(Optional.empty());
        when(client.lookupUser("ana.silva@gmail.com")).thenReturn(new LookupOutcome.NotFound());

        assertThat(service.prepareReset("ana.silva@gmail.com")).isEqualTo(ResetResult.UNKNOWN);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.RESET_UNKNOWN_USER);
            assertThat(event.maskedLogin()).isEqualTo("ana***@gmail");
            assertThat(event.flow()).isEqualTo("reset");
        });
        verify(store, never()).createWithoutPassword(any());
    }

    @Test
    void prepareReset_WhenAlreadyMigratedAndMissingInKeycloak_AuditsAlreadyMigrated() {
        when(store.find("ana.souza")).thenReturn(Optional.empty());
        when(client.lookupUser("ana.souza")).thenReturn(new LookupOutcome.Migrated());

        assertThat(service.prepareReset("ana.souza")).isEqualTo(ResetResult.UNKNOWN);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_ALREADY_MIGRATED);
            assertThat(event.flow()).isEqualTo("reset");
        });
    }

    @Test
    void prepareReset_WhenLegadoUnavailable_AuditsUnavailableWithReasonAndCreatesNothing() {
        when(store.find("ana.souza")).thenReturn(Optional.empty());
        when(client.lookupUser("ana.souza")).thenReturn(new LookupOutcome.Unavailable(FailureReason.TIMEOUT));

        assertThat(service.prepareReset("ana.souza")).isEqualTo(ResetResult.UNKNOWN);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_UNAVAILABLE);
            assertThat(event.error()).isEqualTo("timeout");
            assertThat(event.flow()).isEqualTo("reset");
        });
        verify(store, never()).createWithoutPassword(any());
    }

    @Test
    void prepareReset_WhenRaceCreatedTheUser_FollowsAsExisting() {
        when(store.find("ana.souza"))
                .thenReturn(Optional.empty(), Optional.of(new ExistingUser(KC_ID, false, LEGACY_ID.toString())));
        when(client.lookupUser("ana.souza")).thenReturn(new LookupOutcome.Found(LEGACY_USER));
        when(store.createWithoutPassword(LEGACY_USER)).thenThrow(new DuplicateUserException());

        assertThat(service.prepareReset("ana.souza")).isEqualTo(ResetResult.EXISTING);

        assertThat(events).isEmpty();
    }

    @Test
    void prepareReset_WhenDuplicateIsARealConflict_IsUnknownAndAuditsFailure() {
        when(store.find("ana.souza")).thenReturn(Optional.empty());
        when(client.lookupUser("ana.souza")).thenReturn(new LookupOutcome.Found(LEGACY_USER));
        when(store.createWithoutPassword(LEGACY_USER)).thenThrow(new DuplicateUserException());

        assertThat(service.prepareReset("ana.souza")).isEqualTo(ResetResult.UNKNOWN);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_UNAVAILABLE);
            assertThat(event.error()).isEqualTo("migration_failed");
        });
    }

    @Test
    void prepareReset_WhenUserProfileRejectsData_IsUnknownAndNeverThrows() {
        when(store.find("ana.souza")).thenReturn(Optional.empty());
        when(client.lookupUser("ana.souza")).thenReturn(new LookupOutcome.Found(LEGACY_USER));
        when(store.createWithoutPassword(LEGACY_USER)).thenThrow(new InvalidProfileException(Set.of("mobilePhone")));

        assertThat(service.prepareReset("ana.souza")).isEqualTo(ResetResult.UNKNOWN);

        assertThat(events).singleElement().satisfies(event -> assertThat(event.error()).isEqualTo("invalid_profile"));
    }

    @Test
    void prepareReset_WhenStoreFailsUnexpectedly_IsUnknownAndNeverThrows() {
        when(store.find("ana.souza")).thenReturn(Optional.empty());
        when(client.lookupUser("ana.souza")).thenReturn(new LookupOutcome.Found(LEGACY_USER));
        when(store.createWithoutPassword(LEGACY_USER)).thenThrow(new IllegalStateException("db"));

        assertThat(service.prepareReset("ana.souza")).isEqualTo(ResetResult.UNKNOWN);

        assertThat(events).singleElement().satisfies(event -> assertThat(event.error()).isEqualTo("migration_failed"));
    }
}
