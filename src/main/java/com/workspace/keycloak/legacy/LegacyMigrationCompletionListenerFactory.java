package com.workspace.keycloak.legacy;

import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Registra o listener {@value #PROVIDER_ID} (ADR-0006).
 *
 * <p>Opções em {@code KC_SPI_EVENTS_LISTENER__LEGACY_MIGRATION_COMPLETION__*}, as mesmas do cliente do Legado
 * (ver {@link LegacyClientSettings}). Configuração ausente ou inválida: o listener fica inerte (um erro no log, sem
 * segredo).</p>
 */
public final class LegacyMigrationCompletionListenerFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "legacy-migration-completion";

    private static final Logger LOG = Logger.getLogger(LegacyMigrationCompletionListenerFactory.class);

    private LegacyUserClient client;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new LegacyMigrationCompletionListener(session, client, () -> MigrationAuditPublisher.forSession(session));
    }

    @Override
    public void init(Config.Scope config) {
        client = LegacyClientConfigSupport.createClient(config, PROVIDER_ID, LOG);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Sem dependências de outros providers.
    }

    @Override
    public void close() {
        // O cliente HTTP do JDK não guarda recursos que precisem de fechamento explícito.
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return LegacyClientConfigSupport.configMetadata();
    }
}
