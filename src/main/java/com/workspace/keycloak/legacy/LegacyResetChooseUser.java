package com.workspace.keycloak.legacy;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.resetcred.ResetCredentialChooseUser;
import org.keycloak.models.KeycloakSession;

/**
 * Escolha do usuário no reset de senha com preparação do usuário que só existe no Legado (RN-06, ADR-0006, provider ID
 * {@value LegacyResetChooseUserFactory#PROVIDER_ID}). Estende {@link ResetCredentialChooseUser} e sobrescreve só
 * {@link #action}: antes da lógica padrão, {@link LegacyMigrationService#prepareReset} cria o usuário sem senha se o
 * Legado o conhecer. Depois, o Keycloak segue como sempre: usuário encontrado recebe o e-mail; inexistente não recebe
 * nada, e a tela de sucesso é a mesma nos dois casos (anti-enumeração).
 *
 * <p>Sem cliente do Legado configurado ({@code client == null}) o comportamento é o padrão.</p>
 */
public final class LegacyResetChooseUser extends ResetCredentialChooseUser {

    private static final Logger LOG = Logger.getLogger(LegacyResetChooseUser.class);
    private static final String FORM_USERNAME = "username";

    private final LegacyUserClient client;

    public LegacyResetChooseUser(LegacyUserClient client) {
        this.client = client;
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        if (client != null) {
            prepare(context);
        }
        super.action(context);
    }

    private void prepare(AuthenticationFlowContext context) {
        try {
            String username = context.getHttpRequest().getDecodedFormParameters().getFirst(FORM_USERNAME);
            if (username != null && !username.isBlank()) {
                KeycloakSession session = context.getSession();
                new LegacyMigrationService(client, new KeycloakMigrationUserStore(session),
                        MigrationAuditPublisher.forSession(session)).prepareReset(username);
            }
        } catch (RuntimeException e) {
            // O reset nunca revela falha ao usuário: segue o fluxo padrão (que não envia e-mail se não há usuário).
            LOG.errorf("Legacy reset preparation failed: %s", e.getClass().getName());
        }
    }
}
