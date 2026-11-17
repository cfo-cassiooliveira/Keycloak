package com.workspace.keycloak.legacy;

import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Registra o authenticator {@value #PROVIDER_ID} (classe base do formulário: {@code UsernamePasswordForm}, ADR-0006).
 *
 * <p>Opções em {@code KC_SPI_AUTHENTICATOR__LEGACY_USERNAME_PASSWORD_FORM__*} (ver {@link LegacyClientSettings}). O
 * cliente do Legado é criado uma vez, aqui no {@code init}. Configuração ausente ou inválida: falha fechada, o
 * formulário não migra ninguém e age como o padrão (um erro no log, sem segredo).</p>
 */
public final class LegacyUsernamePasswordFormFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "legacy-username-password-form";

    private static final Logger LOG = Logger.getLogger(LegacyUsernamePasswordFormFactory.class);

    private LegacyUserClient client;

    @Override
    public Authenticator create(KeycloakSession session) {
        return new LegacyUsernamePasswordForm(session, client);
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
    public String getDisplayType() {
        return "Legacy Username Password Form";
    }

    @Override
    public String getReferenceCategory() {
        return PasswordCredentialModel.TYPE;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return UsernamePasswordFormFactory.REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Validates a username and password, migrating the user from the Legado on first login.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return LegacyClientConfigSupport.configMetadata();
    }
}
