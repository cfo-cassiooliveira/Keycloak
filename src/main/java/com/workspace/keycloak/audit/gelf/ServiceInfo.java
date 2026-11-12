package com.workspace.keycloak.audit.gelf;

import java.util.Objects;

/**
 * Identificação do serviço em todo log enviado ao Graylog (campos padrão do steering de observabilidade).
 *
 * @param serviceName     nome lógico do serviço (stream do Graylog)
 * @param serviceVersion  versão das extensões do Workspace
 * @param keycloakVersion versão do servidor Keycloak
 * @param environment     ambiente (local, homologação, produção)
 * @param host            host/container que gerou o log
 */
public record ServiceInfo(String serviceName, String serviceVersion, String keycloakVersion, String environment,
        String host) {

    public ServiceInfo {
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(serviceVersion, "serviceVersion");
        Objects.requireNonNull(keycloakVersion, "keycloakVersion");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(host, "host");
    }
}
