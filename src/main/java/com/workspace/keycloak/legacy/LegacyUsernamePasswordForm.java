package com.workspace.keycloak.legacy;

import jakarta.ws.rs.core.MultivaluedMap;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AuthenticationManager;

/**
 * Formulário de usuário e senha com migração sob demanda do Legado (ADR-0006, provider ID
 * {@value LegacyUsernamePasswordFormFactory#PROVIDER_ID}). Estende {@link UsernamePasswordForm} e sobrescreve só
 * {@link #validateForm}: antes da validação padrão, {@link LegacyMigrationService} cria o usuário se o Legado confirmar
 * as credenciais. A validação em si, os eventos e o erro "Usuário ou senha inválidos." continuam os do Keycloak.
 *
 * <p>Sem cliente do Legado configurado ({@code client == null}) o comportamento é o do formulário padrão.</p>
 */
public final class LegacyUsernamePasswordForm extends UsernamePasswordForm {

    private final LegacyUserClient client;

    public LegacyUsernamePasswordForm(KeycloakSession session, LegacyUserClient client) {
        super(session);
        this.client = client;
    }

    @Override
    protected boolean validateForm(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        // Usuário já definido antes deste passo (reautenticação): é um usuário do Keycloak, nada a migrar.
        if (client != null && !isUserAlreadySetBeforeUsernamePasswordAuth(context)) {
            KeycloakSession session = context.getSession();
            // A marcação do Legado (RN-07) é feita pelo serviço, logo após a migração confirmada.
            new LegacyMigrationService(client, new KeycloakMigrationUserStore(session),
                    MigrationAuditPublisher.forSession(session))
                    .prepareLogin(formData.getFirst(AuthenticationManager.FORM_USERNAME),
                            formData.getFirst("password"));
        }
        return super.validateForm(context, formData);
    }
}
