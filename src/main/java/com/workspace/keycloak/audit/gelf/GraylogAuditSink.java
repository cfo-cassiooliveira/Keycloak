package com.workspace.keycloak.audit.gelf;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import com.workspace.keycloak.audit.AuditEventMapper;
import com.workspace.keycloak.audit.AuditLevel;
import com.workspace.keycloak.audit.AuditRecord;
import com.workspace.keycloak.audit.AuditSink;

/**
 * Envia os registros de auditoria ao Graylog e, se não conseguir, salva no arquivo de fallback local.
 *
 * <p>O envio é assíncrono (fila limitada + uma thread): um Graylog lento ou fora do ar nunca atrasa o login.
 * Nenhum registro é perdido: falha no envio, fila cheia ou desligamento do Keycloak levam ao fallback; se até o
 * arquivo falhar, o registro (que não tem dado pessoal) vai inteiro para o log do container.</p>
 */
public final class GraylogAuditSink implements AuditSink {

    /** Evento enviado ao Graylog quando ele volta, para que o uso do fallback gere alerta. */
    static final String FALLBACK_USED_EVENT = "AUDIT_FALLBACK_USED";

    private final GelfMessageFactory messageFactory;
    private final GelfTransport transport;
    private final AuditFallbackStore fallbackStore;
    private final DeliveryFailureReporter reporter;
    private final Logger logger;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final Duration shutdownTimeout;

    GraylogAuditSink(GelfMessageFactory messageFactory, GelfTransport transport, AuditFallbackStore fallbackStore,
            Logger logger, Clock clock, int queueCapacity, Duration shutdownTimeout) {
        this.messageFactory = messageFactory;
        this.transport = transport;
        this.fallbackStore = fallbackStore;
        this.logger = logger;
        this.clock = clock;
        this.reporter = new DeliveryFailureReporter(logger, clock, Duration.ofMinutes(1));
        this.shutdownTimeout = shutdownTimeout;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "workspace-audit-graylog");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Cria o destino com transporte HTTP e relógio do sistema. */
    public static GraylogAuditSink create(GraylogSettings settings, ServiceInfo serviceInfo, Logger logger) {
        return new GraylogAuditSink(new GelfMessageFactory(serviceInfo),
                new GelfHttpTransport(settings.endpoint(), settings.timeout()),
                new AuditFallbackStore(settings.fallbackFile()), logger, Clock.systemUTC(),
                settings.queueCapacity(), settings.timeout().multipliedBy(2));
    }

    @Override
    public void write(AuditRecord auditRecord) {
        String gelfMessage = messageFactory.toGelf(auditRecord);
        try {
            executor.execute(new Delivery(gelfMessage));
        } catch (RejectedExecutionException e) {
            saveToFallback(gelfMessage, new IOException("Audit delivery queue is full or closed", e));
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // O que não foi entregue a tempo vai para o fallback: o desligamento não perde registros.
        List<Runnable> pending = executor.shutdownNow();
        IOException shuttingDown = new IOException("Keycloak is shutting down before the audit log was delivered");
        pending.stream()
                .filter(Delivery.class::isInstance)
                .map(Delivery.class::cast)
                .forEach(delivery -> saveToFallback(delivery.gelfMessage(), shuttingDown));
    }

    private void deliver(String gelfMessage) {
        try {
            transport.send(gelfMessage);
        } catch (IOException | RuntimeException e) {
            saveToFallback(gelfMessage, e);
            return;
        }
        long recovered = reporter.deliverySucceeded(transport.destination(), fallbackStore.file());
        if (recovered > 0) {
            deliver(messageFactory.toGelf(fallbackUsedRecord(recovered)));
        }
    }

    private void saveToFallback(String gelfMessage, Exception cause) {
        try {
            fallbackStore.append(gelfMessage);
            reporter.fallbackPerformed(cause, transport.destination(), fallbackStore.file());
        } catch (IOException fallbackFailure) {
            fallbackFailure.addSuppressed(cause);
            logger.errorf(fallbackFailure,
                    "Could not save audit log to Graylog nor to the fallback file %s; record kept in this log: %s",
                    fallbackStore.file(), gelfMessage);
        }
    }

    private AuditRecord fallbackUsedRecord(long recovered) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("audit_schema", AuditEventMapper.AUDIT_SCHEMA_VERSION);
        fields.put("kind", "system");
        fields.put("type", FALLBACK_USED_EVENT);
        fields.put("fallback_count", recovered);
        fields.put("fallback_file", fallbackStore.file().toString());
        fields.put("occurred_at", clock.instant().toString());
        return new AuditRecord(AuditLevel.WARN, fields);
    }

    /** Tarefa de entrega que guarda a mensagem, para recuperá-la se o Keycloak desligar antes do envio. */
    private final class Delivery implements Runnable {

        private final String gelfMessage;

        private Delivery(String gelfMessage) {
            this.gelfMessage = gelfMessage;
        }

        String gelfMessage() {
            return gelfMessage;
        }

        @Override
        public void run() {
            deliver(gelfMessage);
        }
    }
}
