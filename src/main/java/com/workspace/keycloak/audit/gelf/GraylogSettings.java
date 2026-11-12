package com.workspace.keycloak.audit.gelf;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Configuração do envio ao Graylog (opções do SPI {@code --spi-events-listener--workspace-audit--*}).
 *
 * <p>Valida tudo na subida do Keycloak (fail fast): uma URL errada não pode virar um "Graylog indisponível" eterno,
 * com todos os registros indo para o fallback.</p>
 *
 * @param endpoint      URL do input GELF HTTP (ex.: http://graylog:12202/gelf)
 * @param timeout       timeout de conexão e de resposta por mensagem
 * @param queueCapacity tamanho máximo da fila de envio; acima disso o registro vai direto ao fallback
 * @param fallbackFile  arquivo local que recebe o que não chegou ao Graylog
 */
public record GraylogSettings(URI endpoint, Duration timeout, int queueCapacity, Path fallbackFile) {

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");

    public GraylogSettings {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(fallbackFile, "fallbackFile");
        if (endpoint.getScheme() == null || !SUPPORTED_SCHEMES.contains(endpoint.getScheme().toLowerCase())
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException(
                    "graylog-url must be an absolute http(s) URL such as http://graylog:12202/gelf, got: " + endpoint);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("graylog-timeout-ms must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("graylog-queue-capacity must be positive");
        }
    }
}
