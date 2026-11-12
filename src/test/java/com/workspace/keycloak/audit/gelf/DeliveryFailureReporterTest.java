package com.workspace.keycloak.audit.gelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

class DeliveryFailureReporterTest {

    private static final String DESTINATION = "http://graylog:12202/gelf";
    private static final Path FALLBACK = Path.of("/opt/keycloak/data/log/audit-fallback.log");
    private static final String FAILURE_MESSAGE =
            "Could not save audit log to Graylog at %s; fallback performed: %d record(s) saved to %s";

    private final Logger logger = mock(Logger.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-12T10:00:00Z"));
    private final DeliveryFailureReporter reporter = new DeliveryFailureReporter(logger, clock, Duration.ofMinutes(1));
    private final IOException cause = new IOException("Connection refused");

    @Test
    void fallbackPerformed_OnFirstFailure_LogsErrorWithCauseImmediately() {
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);

        verify(logger).errorf(cause, FAILURE_MESSAGE, DESTINATION, 1L, FALLBACK);
    }

    @Test
    void fallbackPerformed_DuringTheSameInterval_SummarizesInsteadOfLoggingEveryRecord() {
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);
        clock.advance(Duration.ofSeconds(20));
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);
        clock.advance(Duration.ofSeconds(40));
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);

        verify(logger).errorf(cause, FAILURE_MESSAGE, DESTINATION, 1L, FALLBACK);
        // Os três registros do minuto seguinte saem num único resumo.
        verify(logger).errorf(cause, FAILURE_MESSAGE, DESTINATION, 3L, FALLBACK);
        verify(logger, times(2)).errorf(any(Throwable.class), anyString(), any(), anyLong(), any());
    }

    @Test
    void deliverySucceeded_AfterAnOutage_LogsRecoveryAndReturnsTheOutageTotal() {
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);

        long recovered = reporter.deliverySucceeded(DESTINATION, FALLBACK);

        assertThat(recovered).isEqualTo(2);
        verify(logger).infof("Graylog is reachable again at %s; %d audit record(s) were saved to %s during the outage",
                DESTINATION, 2L, FALLBACK);
    }

    @Test
    void deliverySucceeded_WithoutOutage_ReturnsZeroAndLogsNothing() {
        assertThat(reporter.deliverySucceeded(DESTINATION, FALLBACK)).isZero();

        verify(logger, never()).infof(anyString(), any(), anyLong(), any());
    }

    @Test
    void fallbackPerformed_AfterRecovery_ReportsTheNewOutageImmediately() {
        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);
        reporter.deliverySucceeded(DESTINATION, FALLBACK);

        reporter.fallbackPerformed(cause, DESTINATION, FALLBACK);

        verify(logger, times(2)).errorf(eq(cause), eq(FAILURE_MESSAGE), eq(DESTINATION), eq(1L), eq(FALLBACK));
    }
}
