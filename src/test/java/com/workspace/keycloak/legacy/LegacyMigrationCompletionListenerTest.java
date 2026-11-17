package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.credential.PasswordCredentialModel;

import com.workspace.keycloak.audit.MigrationAuditEntry;
import com.workspace.keycloak.audit.MigrationAuditEvent;

class LegacyMigrationCompletionListenerTest {

    private static final String REALM_ID = "workspace";
    private static final String KC_ID = "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b";
    private static final String LEGACY_ID = "0b2f4c1e-8a3d-4e5f-9a6b-7c8d9e0f1a2b";

    private final KeycloakSession session = mock(KeycloakSession.class);
    private final KeycloakTransactionManager transactions = mock(KeycloakTransactionManager.class);
    private final UserModel user = mock(UserModel.class);
    private final SubjectCredentialManager credentials = mock(SubjectCredentialManager.class);
    private final LegacyUserClient client = mock(LegacyUserClient.class);
    private final List<MigrationAuditEntry> events = new ArrayList<>();
    private final List<KeycloakTransaction> enlisted = new ArrayList<>();
    private final LegacyMigrationCompletionListener listener = new LegacyMigrationCompletionListener(session, client,
            () -> new MigrationAuditPublisher(events::add));

    @BeforeEach
    void setUp() {
        KeycloakContext context = mock(KeycloakContext.class);
        RealmProvider realms = mock(RealmProvider.class);
        UserProvider users = mock(UserProvider.class);
        RealmModel realm = mock(RealmModel.class);
        when(session.getContext()).thenReturn(context);
        when(session.realms()).thenReturn(realms);
        when(realms.getRealm(REALM_ID)).thenReturn(realm);
        when(session.users()).thenReturn(users);
        when(users.getUserById(realm, KC_ID)).thenReturn(user);
        when(user.getId()).thenReturn(KC_ID);
        when(user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID)).thenReturn(LEGACY_ID);
        when(user.credentialManager()).thenReturn(credentials);
        when(credentials.isConfiguredFor(PasswordCredentialModel.TYPE)).thenReturn(true);
        when(session.getTransactionManager()).thenReturn(transactions);
        org.mockito.Mockito.doAnswer(invocation -> enlisted.add(invocation.getArgument(0)))
                .when(transactions).enlistAfterCompletion(any());
    }

    private static Event event(EventType type) {
        Event event = new Event();
        event.setType(type);
        event.setRealmId(REALM_ID);
        event.setUserId(KC_ID);
        return event;
    }

    @Test
    void onEvent_WhenPasswordUpdated_MarksOnlyAfterCommit() {
        when(client.markMigrated(UUID.fromString(LEGACY_ID), UUID.fromString(KC_ID)))
                .thenReturn(new MarkOutcome.Marked());

        listener.onEvent(event(EventType.UPDATE_PASSWORD));

        assertThat(enlisted).hasSize(1);
        verifyNoInteractions(client);
        enlisted.getFirst().commit();
        verify(client).markMigrated(UUID.fromString(LEGACY_ID), UUID.fromString(KC_ID));
        assertThat(events).singleElement()
                .satisfies(entry -> assertThat(entry.type()).isEqualTo(MigrationAuditEvent.LEGACY_MIGRATION_MARKED));
    }

    @Test
    void onEvent_WhenTransactionRollsBack_NeverCallsLegado() {
        listener.onEvent(event(EventType.RESET_PASSWORD));

        enlisted.getFirst().rollback();

        verifyNoInteractions(client);
        assertThat(events).isEmpty();
    }

    @Test
    void onEvent_WhenLegadoFailsAfterCommit_AuditsFailureAndDoesNotThrow() {
        when(client.markMigrated(any(), any())).thenReturn(new MarkOutcome.Unavailable(FailureReason.TIMEOUT));
        listener.onEvent(event(EventType.UPDATE_CREDENTIAL));

        assertThatCode(() -> enlisted.getFirst().commit()).doesNotThrowAnyException();

        assertThat(events).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED);
            assertThat(entry.error()).isEqualTo("timeout");
        });
    }

    @Test
    void onEvent_WhenAlreadyEnlistedInThisRequest_DoesNotEnlistAgain() {
        when(session.getAttribute(org.mockito.ArgumentMatchers.anyString())).thenReturn(Boolean.TRUE);

        listener.onEvent(event(EventType.UPDATE_CREDENTIAL));

        assertThat(enlisted).isEmpty();
    }

    @Test
    void onEvent_WhenUserIsNative_NeverEnlists() {
        when(user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID)).thenReturn(null);

        listener.onEvent(event(EventType.UPDATE_PASSWORD));

        assertThat(enlisted).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void onEvent_WhenUserHasNoPassword_NeverEnlists() {
        when(credentials.isConfiguredFor(PasswordCredentialModel.TYPE)).thenReturn(false);

        listener.onEvent(event(EventType.UPDATE_PASSWORD));

        assertThat(enlisted).isEmpty();
    }

    @Test
    void onEvent_WhenEventTypeIsUnrelated_IsIgnored() {
        listener.onEvent(event(EventType.LOGIN_ERROR));
        listener.onEvent(event(EventType.SEND_RESET_PASSWORD));

        assertThat(enlisted).isEmpty();
        verify(session, never()).users();
    }

    @Test
    void onEvent_WhenEventHasNoUser_IsIgnored() {
        Event event = event(EventType.UPDATE_PASSWORD);
        event.setUserId(null);

        listener.onEvent(event);

        assertThat(enlisted).isEmpty();
    }

    @Test
    void onEvent_WhenClientIsNotConfigured_IsInert() {
        LegacyMigrationCompletionListener inert = new LegacyMigrationCompletionListener(session, null,
                () -> new MigrationAuditPublisher(events::add));

        inert.onEvent(event(EventType.UPDATE_PASSWORD));

        assertThat(enlisted).isEmpty();
    }

    @Test
    void onEvent_WhenSessionFails_DoesNotPropagate() {
        when(session.realms()).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> listener.onEvent(event(EventType.UPDATE_PASSWORD))).doesNotThrowAnyException();
    }
}
