package com.workspace.keycloak.legacy;

import java.util.Set;
import java.util.function.Supplier;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;

/**
 * Avisa o Legado que o usuário migrado passou a ter senha no Keycloak (RN-07, ADR-0006) quando a senha é definida:
 * {@code RESET_PASSWORD} (conta "casca" do reset), {@code UPDATE_PASSWORD} e {@code UPDATE_CREDENTIAL}. A migração
 * no login marca o Legado por conta própria ({@link LegacyMigrationService}); como o PUT é idempotente, uma nova
 * marcação aqui é inofensiva.
 * Só vale para usuário com o atributo {@code legacyUserId} e com senha. A chamada HTTP roda <b>depois do commit</b>
 * da transação do Keycloak ({@code enlistAfterCompletion}) e nunca em rollback. Nunca propaga exceção ao fluxo.
 *
 * <p>Depois do commit para o Legado não ser marcado por uma senha que não foi gravada. Custos: se a chamada falhar,
 * não há nova tentativa automática, só o evento {@code LEGACY_MIGRATION_MARK_FAILED}; e cada troca de senha de conta
 * vinda do Legado repete a marcação, com a espera da chamada na requisição.</p>
 */
public final class LegacyMigrationCompletionListener implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(LegacyMigrationCompletionListener.class);
    private static final Set<EventType> PASSWORD_EVENTS =
            Set.of(EventType.UPDATE_PASSWORD, EventType.UPDATE_CREDENTIAL, EventType.RESET_PASSWORD);

    private final KeycloakSession session;
    private final LegacyUserClient client;
    private final Supplier<MigrationAuditPublisher> audit;

    LegacyMigrationCompletionListener(KeycloakSession session, LegacyUserClient client,
            Supplier<MigrationAuditPublisher> audit) {
        this.session = session;
        this.client = client;
        this.audit = audit;
    }

    @Override
    public void onEvent(Event event) {
        if (client == null || event.getUserId() == null || event.getType() == null) {
            return;
        }
        try {
            handle(event);
        } catch (RuntimeException e) {
            LOG.errorf("Legacy migration completion failed: %s", e.getClass().getName());
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Eventos administrativos não concluem login nem reset.
    }

    private void handle(Event event) {
        if (!PASSWORD_EVENTS.contains(event.getType())) {
            return;
        }
        RealmModel realm = session.realms().getRealm(event.getRealmId());
        UserModel user = realm == null ? null : session.users().getUserById(realm, event.getUserId());
        if (user == null) {
            return;
        }
        String legacyUserId = user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID);
        if (legacyUserId == null || !user.credentialManager().isConfiguredFor(PasswordCredentialModel.TYPE)) {
            return;
        }
        // UPDATE_PASSWORD e UPDATE_CREDENTIAL saem juntos na mesma requisição: uma só marcação por usuário e requisição.
        String requestKey = "legacy-migration-mark:" + user.getId();
        if (session.getAttribute(requestKey) == null) {
            session.setAttribute(requestKey, Boolean.TRUE);
            enlistMark(legacyUserId, user.getId());
        }
    }

    // O commit() da transação "depois da conclusão" só roda se a principal foi confirmada; no rollback nada é chamado.
    private void enlistMark(String legacyUserId, String keycloakUserId) {
        MigrationAuditPublisher publisher = audit.get();
        session.getTransactionManager().enlistAfterCompletion(new KeycloakTransaction() {
            @Override
            public void begin() {
                // Sem estado de transação: só dispara a marcação.
            }

            @Override
            public void commit() {
                new LegacyMigrationMarker(client, publisher).mark(legacyUserId, keycloakUserId);
            }

            @Override
            public void rollback() {
                // Transação principal desfeita: o usuário não ganhou a senha, então o Legado não é avisado.
            }

            @Override
            public void setRollbackOnly() {
                // Esta "transação" nunca falha: erros do Legado viram evento de auditoria.
            }

            @Override
            public boolean getRollbackOnly() {
                return false;
            }

            @Override
            public boolean isActive() {
                return true;
            }
        });
    }

    @Override
    public void close() {
        // Sem recursos por sessão para liberar.
    }
}
