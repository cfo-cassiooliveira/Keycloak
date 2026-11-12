package com.workspace.keycloak.audit;

import java.util.function.Supplier;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Listener de eventos que registra auditoria sem dados pessoais.
 * Nunca propaga exceção: uma falha de auditoria não pode interromper o login (RN-03).
 */
public final class WorkspaceAuditEventListenerProvider implements EventListenerProvider {

    private final AuditEventMapper mapper;
    private final AuditSink sink;
    private final Logger failureLogger;

    public WorkspaceAuditEventListenerProvider(AuditEventMapper mapper, AuditSink sink, Logger failureLogger) {
        this.mapper = mapper;
        this.sink = sink;
        this.failureLogger = failureLogger;
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
            sink.write(withTrace(auditRecord.get()));
        } catch (RuntimeException e) {
            failureLogger.error("Failed to write audit record", e);
        }
    }

    // Correlação com a requisição (W3C Trace Context) quando o tracing do Keycloak está ligado; capturada aqui
    // porque o envio ao Graylog acontece em outra thread, fora do contexto da requisição.
    private static AuditRecord withTrace(AuditRecord auditRecord) {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) {
            return auditRecord;
        }
        return auditRecord.withField("trace_id", span.getTraceId()).withField("span_id", span.getSpanId());
    }
}
