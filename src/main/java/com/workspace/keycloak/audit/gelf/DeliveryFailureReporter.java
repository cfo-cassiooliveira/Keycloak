package com.workspace.keycloak.audit.gelf;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.jboss.logging.Logger;

/**
 * Informa no log do container quando um registro não pôde ser salvo no Graylog e foi para o fallback local.
 *
 * <p>Numa queda longa do Graylog, cada login geraria uma linha de erro: o primeiro fallback é informado na hora e,
 * enquanto a falha durar, no máximo um resumo por intervalo com a contagem do período. Quando o Graylog volta,
 * informa a recuperação com o total do período de falha.</p>
 */
final class DeliveryFailureReporter {

    private final Logger logger;
    private final Clock clock;
    private final Duration reportInterval;

    private boolean failing;
    private long fallbacksSinceLastReport;
    private long fallbacksDuringOutage;
    private Instant lastReportAt;

    DeliveryFailureReporter(Logger logger, Clock clock, Duration reportInterval) {
        this.logger = logger;
        this.clock = clock;
        this.reportInterval = reportInterval;
    }

    synchronized void fallbackPerformed(Exception cause, String destination, Path fallbackFile) {
        fallbacksSinceLastReport++;
        fallbacksDuringOutage++;
        Instant now = clock.instant();
        if (!failing || !now.isBefore(lastReportAt.plus(reportInterval))) {
            logger.errorf(cause,
                    "Could not save audit log to Graylog at %s; fallback performed: %d record(s) saved to %s",
                    destination, fallbacksSinceLastReport, fallbackFile);
            fallbacksSinceLastReport = 0;
            lastReportAt = now;
        }
        failing = true;
    }

    /**
     * Registra uma entrega bem-sucedida.
     *
     * @return quantos registros foram para o fallback durante a falha que acabou de terminar (0 se não havia falha)
     */
    synchronized long deliverySucceeded(String destination, Path fallbackFile) {
        if (!failing) {
            return 0;
        }
        long total = fallbacksDuringOutage;
        logger.infof("Graylog is reachable again at %s; %d audit record(s) were saved to %s during the outage",
                destination, total, fallbackFile);
        failing = false;
        fallbacksSinceLastReport = 0;
        fallbacksDuringOutage = 0;
        return total;
    }
}
