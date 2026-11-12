package com.workspace.keycloak.support;

import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

/** Sobe o Keycloak de teste com as extensões compiladas deste projeto. */
public class WorkspaceServerConfig implements KeycloakServerConfig {

    @Override
    public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
        return config.dependencyCurrentProject();
    }
}
