package com.workspace.keycloak.audit.gelf;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuração do envio ao Graylog (opções do SPI {@code --spi-events-listener--workspace-audit--*}).
 *
 * @param endpoint      URL do input GELF HTTP (ex.: http://graylog:12202/gelf)
 * @param timeout       timeout de conexão e de resposta por mensagem
 * @param queueCapacity tamanho máximo da fila de envio; acima disso o registro vai direto ao fallback
 * @param fallbackFile  arquivo local que recebe o que não chegou ao Graylog
 */
public record GraylogSettings(URI endpoint, Duration timeout, int queueCapacity, Path fallbackFile) {

    public GraylogSettings {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(fallbackFile, "fallbackFile");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
    }
}
