package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;

class AuditEventMapperTest {

    private static final long EVENT_TIME = 1_763_000_000_000L;
    private static final String EVENT_TIME_UTC = "2025-11-13T02:13:20Z";
    private static final String TARGET_USER_ID = "3f0c5d0e-8a4b-4c1e-9f2a-6b7d8e9f0a1b";

    private final AuditEventMapper mapper = new AuditEventMapper();

    @Test
    void map_WhenLoginSucceeds_ReturnsInfoWithOnlyAllowedFields() {
        AuditRecord auditRecord = mapper.map(userEvent(EventType.LOGIN, null));

        assertThat(auditRecord.level()).isEqualTo(AuditLevel.INFO);
        assertThat(auditRecord.fields()).containsExactly(
                Map.entry("audit_schema", 1),
                Map.entry("kind", "user"),
                Map.entry("event_id", "event-1"),
                Map.entry("type", "LOGIN"),
                Map.entry("realm_id", "realm-1"),
                Map.entry("realm_name", "workspace"),
                Map.entry("client_id", "account-console"),
                Map.entry("user_id", "user-1"),
                Map.entry("auth_method", "openid-connect"),
                Map.entry("occurred_at", EVENT_TIME_UTC));
    }

    @Test
    void map_WhenLoginFails_ReturnsWarnWithErrorCode() {
        AuditRecord auditRecord = mapper.map(userEvent(EventType.LOGIN_ERROR, "invalid_user_credentials"));

        assertThat(auditRecord.level()).isEqualTo(AuditLevel.WARN);
        assertThat(auditRecord.fields())
                .containsEntry("type", "LOGIN_ERROR")
                .containsEntry("error", "invalid_user_credentials");
    }

    @Test
    void map_WhenUserEventCarriesPersonalData_DropsEveryFieldOutsideAllowlist() {
        Event event = userEvent(EventType.LOGIN_ERROR, "invalid_user_credentials");

        AuditRecord auditRecord = mapper.map(event);

        // Falha se um campo novo passar a ser registrado sem revisão de LGPD.
        assertThat(auditRecord.fields().keySet()).containsOnly(
                "audit_schema", "kind", "event_id", "type", "realm_id", "realm_name", "client_id", "user_id",
                "error", "auth_method", "occurred_at");
        assertThat(auditRecord.fields().values()).doesNotContain(
                "maria.silva", "maria@example.com", "203.0.113.10", "session-1", "https://app/cb?code=x");
    }

    @Test
    void map_WhenUserEventHasNoDetails_OmitsDetailFields() {
        Event event = userEvent(EventType.LOGOUT, null);
        event.setDetails(null);
        event.setClientId(null);

        AuditRecord auditRecord = mapper.map(event);

        assertThat(auditRecord.fields()).doesNotContainKeys("auth_method", "client_id", "error");
    }

    @Test
    void map_WhenAdminUpdatesUser_ReturnsInfoWithTargetUserIdButNoRepresentationOrPath() {
        AuditRecord auditRecord = mapper.map(adminEvent(null));

        assertThat(auditRecord.level()).isEqualTo(AuditLevel.INFO);
        assertThat(auditRecord.fields()).containsExactly(
                Map.entry("audit_schema", 1),
                Map.entry("kind", "admin"),
                Map.entry("event_id", "admin-event-1"),
                Map.entry("operation", "UPDATE"),
                Map.entry("resource_type", "USER"),
                Map.entry("realm_id", "realm-1"),
                Map.entry("realm_name", "workspace"),
                Map.entry("target_user_id", TARGET_USER_ID),
                Map.entry("actor_user_id", "admin-1"),
                Map.entry("actor_client_id", "security-admin-console"),
                Map.entry("occurred_at", EVENT_TIME_UTC));
    }

    @Test
    void map_WhenAdminChangesUserSubResource_KeepsOnlyTheTargetUserId() {
        AdminEvent event = adminEvent(null);
        event.setResourcePath("users/" + TARGET_USER_ID + "/role-mappings/realm");

        AuditRecord auditRecord = mapper.map(event);

        assertThat(auditRecord.fields()).containsEntry("target_user_id", TARGET_USER_ID);
        assertThat(auditRecord.fields().values()).doesNotContain(event.getResourcePath());
    }

    @Test
    void map_WhenAdminResourceIsNotAUserUuid_OmitsTargetUserId() {
        AdminEvent groupEvent = adminEvent(null);
        groupEvent.setResourcePath("groups/Financeiro");
        AdminEvent malformedUserEvent = adminEvent(null);
        malformedUserEvent.setResourcePath("users/maria.silva");
        AdminEvent noPathEvent = adminEvent(null);
        noPathEvent.setResourcePath(null);

        assertThat(mapper.map(groupEvent).fields()).doesNotContainKey("target_user_id");
        assertThat(mapper.map(malformedUserEvent).fields()).doesNotContainKey("target_user_id");
        assertThat(mapper.map(noPathEvent).fields()).doesNotContainKey("target_user_id");
    }

    @Test
    void map_WhenAdminOperationFails_ReturnsWarnWithError() {
        AuditRecord auditRecord = mapper.map(adminEvent("unknown_error"));

        assertThat(auditRecord.level()).isEqualTo(AuditLevel.WARN);
        assertThat(auditRecord.fields()).containsEntry("error", "unknown_error");
    }

    @Test
    void map_WhenAdminEventHasNoAuthDetails_OmitsActorFields() {
        AdminEvent event = adminEvent(null);
        event.setAuthDetails(null);

        AuditRecord auditRecord = mapper.map(event);

        assertThat(auditRecord.fields()).doesNotContainKeys("actor_user_id", "actor_client_id");
    }

    private static Event userEvent(EventType type, String error) {
        Event event = new Event();
        event.setId("event-1");
        event.setType(type);
        event.setTime(EVENT_TIME);
        event.setRealmId("realm-1");
        event.setRealmName("workspace");
        event.setClientId("account-console");
        event.setUserId("user-1");
        event.setSessionId("session-1");
        event.setIpAddress("203.0.113.10");
        event.setError(error);
        event.setDetails(Map.of(
                "auth_method", "openid-connect",
                "username", "maria.silva",
                "email", "maria@example.com",
                "redirect_uri", "https://app/cb?code=x"));
        return event;
    }

    private static AdminEvent adminEvent(String error) {
        AuthDetails actor = new AuthDetails();
        actor.setRealmId("master-id");
        actor.setClientId("security-admin-console");
        actor.setUserId("admin-1");
        actor.setIpAddress("203.0.113.20");

        AdminEvent event = new AdminEvent();
        event.setId("admin-event-1");
        event.setTime(EVENT_TIME);
        event.setRealmId("realm-1");
        event.setAuthDetails(actor);
        event.setOperationType(OperationType.UPDATE);
        event.setResourceType(ResourceType.USER);
        event.setRealmName("workspace");
        event.setResourcePath("users/" + TARGET_USER_ID);
        event.setRepresentation("{\"email\":\"maria@example.com\"}");
        event.setError(error);
        return event;
    }
}
