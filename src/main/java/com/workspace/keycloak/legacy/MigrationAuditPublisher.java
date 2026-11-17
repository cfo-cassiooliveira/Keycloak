package com.workspace.keycloak.legacy;

import java.util.function.Consumer;

import org.jboss.logging.Logger;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;

import com.workspace.keycloak.audit.MigrationAuditEntry;
import com.workspace.keycloak.audit.WorkspaceAuditEventListenerProvider;
import com.workspace.keycloak.audit.WorkspaceAuditEventListenerProviderFactory;

/**
 * Emite eventos de migração (LEGACY_*, RESET_UNKNOWN_USER) ao mesmo destino da auditoria do Keycloak (Graylog com
 * fallback, ou console), sem passar pelo {@code EventBuilder}.
 *
 * <p>Nunca propaga exceção: auditoria não pode quebrar o login nem o reset (RN-03).</p>
 */
public final class MigrationAuditPublisher {

    private static final Logger LOG = Logger.getLogger(MigrationAuditPublisher.class);

    private final Consumer<MigrationAuditEntry> writer;

    public MigrationAuditPublisher(Consumer<MigrationAuditEntry> writer) {
        this.writer = writer;
    }

    /**
     * Obtém o publicador do listener {@value WorkspaceAuditEventListenerProviderFactory#PROVIDER_ID} já configurado
     * (a mesma instância de sink do listener, sem segunda fila). Se o listener não estiver disponível, os eventos
     * são descartados com um erro no log do servidor.
     */
    public static MigrationAuditPublisher forSession(KeycloakSession session) {
        try {
            EventListenerProvider provider = session.getProvider(EventListenerProvider.class,
                    WorkspaceAuditEventListenerProviderFactory.PROVIDER_ID);
            if (provider instanceof WorkspaceAuditEventListenerProvider audit) {
                String clientId = currentClientId(session);
                return new MigrationAuditPublisher(entry -> audit.onMigrationEvent(
                        entry.clientId() == null ? entry.withClientId(clientId) : entry));
            }
        } catch (RuntimeException e) {
            LOG.error("Audit listener lookup failed", e);
        }
        LOG.error("Audit listener workspace-audit unavailable: migration events will not be recorded");
        return new MigrationAuditPublisher(entry -> { });
    }

    // Client OIDC da autenticação em curso (id técnico, não é dado pessoal); nulo fora de um fluxo de client.
    private static String currentClientId(KeycloakSession session) {
        try {
            KeycloakContext context = session.getContext();
            if (context.getAuthenticationSession() != null && context.getAuthenticationSession().getClient() != null) {
                return context.getAuthenticationSession().getClient().getClientId();
            }
            return context.getClient() == null ? null : context.getClient().getClientId();
        } catch (RuntimeException e) {
            LOG.debug("Could not resolve the client of the current session", e);
            return null;
        }
    }

    public void publish(MigrationAuditEntry entry) {
        try {
            writer.accept(entry);
        } catch (RuntimeException e) {
            LOG.error("Failed to publish migration audit event", e);
        }
    }
}
