package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

class WorkspaceAuditEventListenerProviderTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    private final List<AuditRecord> written = new ArrayList<>();
    private final AuditSink recordingSink = written::add;
    private final Logger failureLogger = mock(Logger.class);

    @Test
    void onEvent_WhenLoginFails_WritesWarnRecordWithoutUsername() {
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper(), recordingSink);
        Event event = new Event();
        event.setType(EventType.LOGIN_ERROR);
        event.setError("invalid_user_credentials");
        event.setDetails(Map.of("username", "maria.silva"));

        provider.onEvent(event);

        assertThat(written).singleElement().satisfies(auditRecord -> {
            assertThat(auditRecord.level()).isEqualTo(AuditLevel.WARN);
            assertThat(auditRecord.fields()).containsEntry("type", "LOGIN_ERROR")
                    .doesNotContainValue("maria.silva");
        });
    }

    @Test
    void onAdminEvent_WhenRepresentationIsIncluded_WritesRecordWithoutRepresentation() {
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper(), recordingSink);
        AdminEvent event = new AdminEvent();
        event.setOperationType(OperationType.CREATE);
        event.setResourceType(ResourceType.USER);
        event.setRepresentation("{\"email\":\"maria@example.com\"}");

        provider.onEvent(event, true);

        assertThat(written).singleElement().satisfies(auditRecord -> assertThat(auditRecord.fields())
                .containsEntry("operation", "CREATE")
                .doesNotContainValue("{\"email\":\"maria@example.com\"}"));
    }

    @Test
    void onEvent_WhenRequestIsTraced_AddsTraceAndSpanIds() {
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper(), recordingSink);
        SpanContext spanContext = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(),
                TraceState.getDefault());

        try (Scope ignored = Span.wrap(spanContext).makeCurrent()) {
            provider.onEvent(loginEvent());
        }

        assertThat(written).singleElement().satisfies(auditRecord -> assertThat(auditRecord.fields())
                .containsEntry("trace_id", TRACE_ID)
                .containsEntry("span_id", SPAN_ID));
    }

    @Test
    void onEvent_WhenRequestIsNotTraced_OmitsTraceFields() {
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper(), recordingSink);

        provider.onEvent(loginEvent());

        assertThat(written).singleElement().satisfies(auditRecord -> assertThat(auditRecord.fields())
                .doesNotContainKeys("trace_id", "span_id"));
    }

    @Test
    void onEvent_WhenMappingFails_LogsErrorOnceAndDoesNotPropagate() {
        AuditEventMapper failingMapper = mock(AuditEventMapper.class);
        IllegalStateException failure = new IllegalStateException("boom");
        when(failingMapper.map(any(Event.class))).thenThrow(failure);
        WorkspaceAuditEventListenerProvider provider = providerWith(failingMapper, recordingSink);

        assertThatCode(() -> provider.onEvent(new Event())).doesNotThrowAnyException();

        verify(failureLogger).error("Failed to write audit record", failure);
        assertThat(written).isEmpty();
    }

    @Test
    void onAdminEvent_WhenSinkFails_LogsErrorAndDoesNotPropagate() {
        AuditSink failingSink = auditRecord -> {
            throw new IllegalStateException("sink down");
        };
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper(), failingSink);
        AdminEvent event = new AdminEvent();
        event.setOperationType(OperationType.DELETE);

        assertThatCode(() -> provider.onEvent(event, false)).doesNotThrowAnyException();

        verify(failureLogger).error(any(Object.class), any(IllegalStateException.class));
    }

    @Test
    void close_DoesNotCloseTheSharedSink() {
        AuditSink sink = mock(AuditSink.class);
        WorkspaceAuditEventListenerProvider provider = providerWith(new AuditEventMapper(), sink);

        provider.close();

        verify(sink, never()).close();
    }

    private static Event loginEvent() {
        Event event = new Event();
        event.setType(EventType.LOGIN);
        event.setUserId("user-1");
        return event;
    }

    private WorkspaceAuditEventListenerProvider providerWith(AuditEventMapper mapper, AuditSink sink) {
        return new WorkspaceAuditEventListenerProvider(mapper, sink, failureLogger);
    }
}
