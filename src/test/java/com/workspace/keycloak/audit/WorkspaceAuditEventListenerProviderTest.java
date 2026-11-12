package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.mockito.ArgumentCaptor;

class WorkspaceAuditEventListenerProviderTest {

    private final Logger logger = mock(Logger.class);

    @Test
    void onEvent_WhenLoginFails_LogsWarnWithoutUsername() {
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper());
        Event event = new Event();
        event.setType(EventType.LOGIN_ERROR);
        event.setError("invalid_user_credentials");
        event.setDetails(Map.of("username", "maria.silva"));

        provider.onEvent(event);

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(logger).warn(message.capture());
        assertThat(message.getValue().toString())
                .contains("\"type\":\"LOGIN_ERROR\"")
                .doesNotContain("maria.silva");
    }

    @Test
    void onAdminEvent_WhenRepresentationIsIncluded_LogsInfoWithoutRepresentation() {
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper());
        AdminEvent event = new AdminEvent();
        event.setOperationType(OperationType.CREATE);
        event.setResourceType(ResourceType.USER);
        event.setRepresentation("{\"email\":\"maria@example.com\"}");

        provider.onEvent(event, true);

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(logger).info(message.capture());
        assertThat(message.getValue().toString())
                .contains("\"operation\":\"CREATE\"")
                .doesNotContain("maria@example.com");
    }

    @Test
    void onEvent_WhenMappingFails_LogsErrorOnceAndDoesNotPropagate() {
        AuditEventMapper failingMapper = mock(AuditEventMapper.class);
        IllegalStateException failure = new IllegalStateException("boom");
        when(failingMapper.map(any(Event.class))).thenThrow(failure);
        WorkspaceAuditEventListenerProvider provider = providerWith(failingMapper);

        assertThatCode(() -> provider.onEvent(new Event())).doesNotThrowAnyException();

        verify(logger).error("Failed to write audit record", failure);
        verify(logger, never()).warn(anyString());
    }

    @Test
    void onAdminEvent_WhenMappingFails_DoesNotPropagate() {
        AuditEventMapper failingMapper = mock(AuditEventMapper.class);
        when(failingMapper.map(any(AdminEvent.class))).thenThrow(new IllegalStateException("boom"));
        WorkspaceAuditEventListenerProvider provider = providerWith(failingMapper);

        assertThatCode(() -> provider.onEvent(new AdminEvent(), false)).doesNotThrowAnyException();
    }

    @Test
    void factory_CreatesSharedProviderWithWorkspaceAuditId() {
        WorkspaceAuditEventListenerProviderFactory factory = new WorkspaceAuditEventListenerProviderFactory();
        KeycloakSession session = mock(KeycloakSession.class);

        factory.init(null);
        factory.postInit(null);

        assertThat(factory.getId()).isEqualTo("workspace-audit");
        assertThat(factory.create(session)).isSameAs(factory.create(session));
        assertThatCode(() -> {
            factory.create(session).close();
            factory.close();
        }).doesNotThrowAnyException();
    }

    private WorkspaceAuditEventListenerProvider providerWith(AuditEventMapper mapper) {
        return new WorkspaceAuditEventListenerProvider(mapper, new AuditLogWriter(logger));
    }
}
