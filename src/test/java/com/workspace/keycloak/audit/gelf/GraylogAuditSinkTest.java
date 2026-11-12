package com.workspace.keycloak.audit.gelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.workspace.keycloak.audit.AuditLevel;
import com.workspace.keycloak.audit.AuditRecord;

class GraylogAuditSinkTest {

    private static final ServiceInfo SERVICE = new ServiceInfo("keycloak", "0.3.0", "26.8.2", "local", "kc-1");
    private static final Duration WAIT = Duration.ofSeconds(5);

    @TempDir
    Path tempDir;

    private final Logger logger = mock(Logger.class);
    private final FakeTransport transport = new FakeTransport();
    private GraylogAuditSink sink;

    @AfterEach
    void closeSink() {
        if (sink != null) {
            sink.close();
        }
    }

    @Test
    void write_WhenGraylogAccepts_DeliversGelfAndUsesNoFallback() {
        sink = sink(10);

        sink.write(loginError("user-1"));

        await().atMost(WAIT).until(() -> transport.delivered.size() == 1);
        assertThat(transport.delivered.getFirst()).contains("\"_user_id\":\"user-1\"", "\"_service_name\":\"keycloak\"");
        assertThat(fallbackFile()).doesNotExist();
        verify(logger, never()).errorf(any(Throwable.class), anyString(), any(), anyLong(), any());
    }

    @Test
    void write_WhenGraylogIsDown_SavesToFallbackAndLogsFallbackPerformed() throws IOException {
        transport.failing = true;
        sink = sink(10);

        sink.write(loginError("user-1"));

        await().atMost(WAIT).until(() -> Files.exists(fallbackFile()) && Files.readAllLines(fallbackFile()).size() == 1);
        assertThat(Files.readAllLines(fallbackFile()).getFirst()).contains("\"_user_id\":\"user-1\"");
        verify(logger).errorf(any(IOException.class), contains("fallback performed"), eq(transport.destination()),
                eq(1L), eq(fallbackFile()));
    }

    @Test
    void write_WhenGraylogComesBack_SendsFallbackUsedEventForAlerting() throws IOException {
        transport.failing = true;
        sink = sink(10);
        sink.write(loginError("user-1"));
        await().atMost(WAIT).until(() -> Files.exists(fallbackFile()));

        transport.failing = false;
        sink.write(loginError("user-2"));

        await().atMost(WAIT).until(() -> transport.delivered.size() == 2);
        assertThat(transport.delivered.get(0)).contains("\"_user_id\":\"user-2\"");
        assertThat(transport.delivered.get(1))
                .contains("\"_type\":\"AUDIT_FALLBACK_USED\"", "\"_fallback_count\":1", "\"_kind\":\"system\"",
                        "\"level\":4");
        verify(logger).infof(anyString(), eq(transport.destination()), eq(1L), eq(fallbackFile()));
    }

    @Test
    void write_WhenQueueIsFull_SavesToFallbackWithoutBlockingTheCaller() throws Exception {
        transport.blockUntilReleased();
        sink = sink(1);
        sink.write(loginError("in-flight"));
        await().atMost(WAIT).until(() -> transport.inFlight.getCount() == 0);

        sink.write(loginError("queued"));
        sink.write(loginError("overflow"));

        assertThat(Files.readAllLines(fallbackFile())).singleElement().asString().contains("\"_user_id\":\"overflow\"");
        transport.release();
    }

    @Test
    void close_WhenDeliveriesArePending_SavesThemToFallback() throws Exception {
        transport.blockUntilReleased();
        sink = sink(10);
        sink.write(loginError("in-flight"));
        await().atMost(WAIT).until(() -> transport.inFlight.getCount() == 0);
        sink.write(loginError("pending-1"));
        sink.write(loginError("pending-2"));

        sink.close();

        List<String> saved = Files.readAllLines(fallbackFile());
        assertThat(saved).anyMatch(line -> line.contains("\"_user_id\":\"pending-1\""))
                .anyMatch(line -> line.contains("\"_user_id\":\"pending-2\""));
        sink = null;
    }

    @Test
    void write_WhenFallbackFileAlsoFails_KeepsTheRecordInTheContainerLog() throws IOException {
        transport.failing = true;
        Path directoryInsteadOfFile = Files.createDirectory(tempDir.resolve("blocked"));
        sink = new GraylogAuditSink(new GelfMessageFactory(SERVICE), transport,
                new AuditFallbackStore(directoryInsteadOfFile), logger, clock(), 10, Duration.ofSeconds(1));

        sink.write(loginError("user-1"));

        await().atMost(WAIT).untilAsserted(() -> verify(logger).errorf(any(IOException.class),
                contains("nor to the fallback file"), eq(directoryInsteadOfFile), contains("\"_user_id\":\"user-1\"")));
    }

    private GraylogAuditSink sink(int queueCapacity) {
        return new GraylogAuditSink(new GelfMessageFactory(SERVICE), transport, new AuditFallbackStore(fallbackFile()),
                logger, clock(), queueCapacity, Duration.ofMillis(300));
    }

    private Path fallbackFile() {
        return tempDir.resolve("log/audit-fallback.log");
    }

    private static MutableClock clock() {
        return new MutableClock(Instant.parse("2026-11-12T10:00:00Z"));
    }

    private static AuditRecord loginError(String userId) {
        return new AuditRecord(AuditLevel.WARN, Map.of("kind", "user", "type", "LOGIN_ERROR", "user_id", userId,
                "occurred_at", "2026-11-12T10:00:00Z"));
    }

    /** Transporte falso: entrega, falha ou trava (para simular Graylog lento) conforme o teste. */
    private static final class FakeTransport implements GelfTransport {

        private final List<String> delivered = new CopyOnWriteArrayList<>();
        private volatile boolean failing;
        private volatile CountDownLatch release;
        private final CountDownLatch inFlight = new CountDownLatch(1);

        void blockUntilReleased() {
            release = new CountDownLatch(1);
        }

        void release() {
            if (release != null) {
                release.countDown();
            }
        }

        @Override
        public void send(String gelfMessage) throws IOException {
            if (release != null) {
                inFlight.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("Fake Graylog never answered");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while sending", e);
                }
            }
            if (failing) {
                throw new IOException("Connection refused");
            }
            delivered.add(gelfMessage);
        }

        @Override
        public String destination() {
            return "http://graylog:12202/gelf";
        }
    }
}
