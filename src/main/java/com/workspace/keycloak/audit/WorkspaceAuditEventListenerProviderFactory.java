package com.workspace.keycloak.audit;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/** Registra o listener {@value #PROVIDER_ID} no Event Listener SPI. */
public final class WorkspaceAuditEventListenerProviderFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "workspace-audit";

    // O provider não guarda estado, então uma única instância atende todas as sessões.
    private final WorkspaceAuditEventListenerProvider provider = new WorkspaceAuditEventListenerProvider(
            new AuditEventMapper(), new AuditLogWriter(Logger.getLogger(AuditLogWriter.CATEGORY)));

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return provider;
    }

    @Override
    public void init(Config.Scope config) {
        // Sem configuração.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Sem dependências de outros providers.
    }

    @Override
    public void close() {
        // Sem recursos para liberar.
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
