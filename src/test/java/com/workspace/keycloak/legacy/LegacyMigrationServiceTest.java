package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.workspace.keycloak.legacy.LegacyMigrationService.Result;
import com.workspace.keycloak.legacy.MigrationUserStore.DuplicateUserException;
import com.workspace.keycloak.legacy.MigrationUserStore.ExistingUser;
import com.workspace.keycloak.legacy.MigrationUserStore.InvalidProfileException;
import com.workspace.keycloak.legacy.MigrationUserStore.StoredUser;

class LegacyMigrationServiceTest {

    private static final UUID LEGACY_ID = UUID.fromString("0b2f4c1e-8a3d-4e5f-9a6b-7c8d9e0f1a2b");
    private static final String KC_ID = "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final String PASSWORD = "Senha-Forte-2026!";
    private static final LegacyUser LEGACY_USER =
            new LegacyUser(LEGACY_ID, "ana.souza", "ana.souza@gmail.com", true, "Ana Souza", null, null, List.of());

    private final LegacyUserClient client = mock(LegacyUserClient.class);
    private final MigrationUserStore store = mock(MigrationUserStore.class);
    private final List<MigrationAuditEntry> events = new ArrayList<>();
    private final LegacyMigrationService service =
            new LegacyMigrationService(client, store, new MigrationAuditPublisher(events::add));

    {
        when(client.markMigrated(any(), any())).thenReturn(new MarkOutcome.Marked());
    }

    private void legacyReplies(VerifyOutcome outcome) {
        when(client.verifyCredentials(anyString(), anyString())).thenReturn(outcome);
    }

    @Test
    void prepareLogin_WhenUserExistsWithPassword_DoesNotCallLegado() {
        when(store.find("ana.souza")).thenReturn(Optional.of(new ExistingUser(KC_ID, true, null)));

        assertThat(service.prepareLogin("Ana.Souza", PASSWORD)).isEqualTo(Result.NATIVE);

        verifyNoInteractions(client);
        assertThat(events).isEmpty();
    }

    @Test
    void prepareLogin_WhenShellHasPasswordEvenWithLegacyId_DoesNotCallLegado() {
        when(store.find("ana.souza")).thenReturn(Optional.of(new ExistingUser(KC_ID, true, LEGACY_ID.toString())));

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.NATIVE);

        verifyNoInteractions(client);
    }

    @Test
    void prepareLogin_WhenUserWithoutPasswordAndWithoutLegacyId_DoesNotCallLegado() {
        when(store.find("ana.souza")).thenReturn(Optional.of(new ExistingUser(KC_ID, false, null)));

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.NATIVE);

        verifyNoInteractions(client);
    }

    @Test
    void prepareLogin_WhenLoginOrPasswordIsEmpty_DoesNothing() {
        assertThat(service.prepareLogin("   ", PASSWORD)).isEqualTo(Result.NATIVE);
        assertThat(service.prepareLogin("ana", "")).isEqualTo(Result.NATIVE);
        assertThat(service.prepareLogin("ana", null)).isEqualTo(Result.NATIVE);

        verifyNoInteractions(client, store);
    }

    @Test
    void prepareLogin_WhenLegadoSaysValid_CreatesUserAndAuditsWithoutPersonalData() {
        when(store.find("ana.souza@gmail.com")).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(LEGACY_USER, null, PASSWORD)).thenReturn(new StoredUser(KC_ID, false));

        assertThat(service.prepareLogin("  Ana.Souza@Gmail.com ", PASSWORD)).isEqualTo(Result.MIGRATED);

        verify(client).verifyCredentials("ana.souza@gmail.com", PASSWORD);
        verify(client).markMigrated(LEGACY_ID, UUID.fromString(KC_ID));
        assertThat(events).hasSize(2).first().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_USER_MIGRATED);
            assertThat(event.userId()).isEqualTo(KC_ID);
            assertThat(event.legacyUserId()).isEqualTo(LEGACY_ID.toString());
            assertThat(event.passwordUpdateRequired()).isFalse();
            assertThat(event.maskedLogin()).isNull();
            assertThat(event.flow()).isEqualTo("login");
        });
    }

    // RN-07: o Legado é marcado logo após a migração confirmada, sem esperar o login concluir.
    @Test
    void prepareLogin_WhenMigrated_MarksLegadoImmediatelyAndAuditsMarked() {
        when(store.find(anyString())).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), anyString())).thenReturn(new StoredUser(KC_ID, false));

        service.prepareLogin("ana.souza", PASSWORD);

        verify(client).markMigrated(LEGACY_ID, UUID.fromString(KC_ID));
        assertThat(events).extracting(MigrationAuditEntry::type).containsExactly(
                MigrationAuditEvent.LEGACY_USER_MIGRATED, MigrationAuditEvent.LEGACY_MIGRATION_MARKED);
    }

    // Rollback da transação interna: o store lança, a senha não existe e o Legado não é marcado.
    @Test
    void prepareLogin_WhenMigrationRollsBack_NeverMarksLegado() {
        when(store.find(anyString())).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), anyString())).thenThrow(new IllegalStateException("db"));

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.REJECTED);

        verify(client, never()).markMigrated(any(), any());
        assertThat(events).extracting(MigrationAuditEntry::type)
                .doesNotContain(MigrationAuditEvent.LEGACY_MIGRATION_MARKED);
    }

    @Test
    void prepareLogin_WhenMarkFailsAfterMigration_StillMigratesAndAuditsFailure() {
        when(store.find(anyString())).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), anyString())).thenReturn(new StoredUser(KC_ID, true));
        when(client.markMigrated(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.MIGRATED);

        assertThat(events).extracting(MigrationAuditEntry::type).containsExactly(
                MigrationAuditEvent.LEGACY_USER_MIGRATED, MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED);
    }

    @Test
    void prepareLogin_WhenPasswordViolatesPolicy_ReportsPasswordUpdateRequired() {
        when(store.find(anyString())).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), anyString())).thenReturn(new StoredUser(KC_ID, true));

        assertThat(service.prepareLogin("ana.souza", "Abc-1234x")).isEqualTo(Result.MIGRATED);

        assertThat(events).first().satisfies(event ->
                assertThat(event.passwordUpdateRequired()).isTrue());
        verify(client).markMigrated(LEGACY_ID, UUID.fromString(KC_ID));
    }

    @Test
    void prepareLogin_WhenShellWithLegacyId_CompletesTheShell() {
        when(store.find("ana.souza")).thenReturn(Optional.of(new ExistingUser(KC_ID, false, LEGACY_ID.toString())));
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(LEGACY_USER, KC_ID, PASSWORD)).thenReturn(new StoredUser(KC_ID, false));

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.MIGRATED);

        verify(store).createOrComplete(LEGACY_USER, KC_ID, PASSWORD);
    }

    @Test
    void prepareLogin_WhenLegadoDoesNotConfirm_CreatesNothingAndAuditsWithMaskedLogin() {
        record Case(VerifyOutcome outcome, MigrationAuditEvent expected, String error) {
        }
        List<Case> cases = List.of(
                new Case(new VerifyOutcome.NotFound(), MigrationAuditEvent.LEGACY_USER_NOT_FOUND, null),
                new Case(new VerifyOutcome.InvalidPassword(), MigrationAuditEvent.LEGACY_PASSWORD_MISMATCH, null),
                new Case(new VerifyOutcome.Locked(), MigrationAuditEvent.LEGACY_ACCOUNT_LOCKED, null),
                new Case(new VerifyOutcome.Migrated(), MigrationAuditEvent.LEGACY_ALREADY_MIGRATED, null),
                new Case(new VerifyOutcome.Unavailable(FailureReason.TIMEOUT), MigrationAuditEvent.LEGACY_UNAVAILABLE,
                        "timeout"));
        when(store.find(anyString())).thenReturn(Optional.empty());

        for (Case c : cases) {
            events.clear();
            legacyReplies(c.outcome());

            assertThat(service.prepareLogin("carla.mendes@gmail.com", PASSWORD)).isEqualTo(Result.REJECTED);

            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo(c.expected());
                assertThat(event.maskedLogin()).isEqualTo("car***@gmail");
                assertThat(event.flow()).isEqualTo("login");
                assertThat(event.error()).isEqualTo(c.error());
            });
        }
        verify(store, never()).createOrComplete(any(), any(), any());
    }

    @Test
    void prepareLogin_WhenCreationLosesTheRace_RereadsAndFollowsTheNormalFlow() {
        when(store.find("ana.souza")).thenReturn(Optional.empty(), Optional.of(new ExistingUser(KC_ID, true, LEGACY_ID.toString())));
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), anyString())).thenThrow(new DuplicateUserException());

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.CONCURRENT);

        assertThat(events).isEmpty();
    }

    @Test
    void prepareLogin_WhenDuplicateButUserStillMissing_RejectsAsMigrationFailed() {
        when(store.find("ana.souza")).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), anyString())).thenThrow(new DuplicateUserException());

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.REJECTED);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_UNAVAILABLE);
            assertThat(event.error()).isEqualTo("migration_failed");
        });
    }

    @Test
    void prepareLogin_WhenUserProfileRejectsLegacyData_RejectsWithDiagnosableEvent() {
        when(store.find(anyString())).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), anyString()))
                .thenThrow(new InvalidProfileException(Set.of("mobilePhone")));

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.REJECTED);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MigrationAuditEvent.LEGACY_UNAVAILABLE);
            assertThat(event.error()).isEqualTo("invalid_profile");
            assertThat(event.maskedLogin()).isEqualTo("ana***");
        });
    }

    @Test
    void prepareLogin_WhenStoreFailsUnexpectedly_RejectsWithoutPropagating() {
        when(store.find(anyString())).thenReturn(Optional.empty());
        legacyReplies(new VerifyOutcome.Valid(LEGACY_USER));
        when(store.createOrComplete(any(), any(), eq(PASSWORD))).thenThrow(new IllegalStateException("db"));

        assertThat(service.prepareLogin("ana.souza", PASSWORD)).isEqualTo(Result.REJECTED);

        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.error()).isEqualTo("migration_failed"));
    }
}
