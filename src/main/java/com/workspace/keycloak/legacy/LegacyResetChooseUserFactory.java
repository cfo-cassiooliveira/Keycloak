package com.workspace.keycloak.legacy;

import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.authentication.authenticators.resetcred.ResetCredentialChooseUser;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Registra o authenticator {@value #PROVIDER_ID} (classe base: {@code ResetCredentialChooseUser}, ADR-0006).
 *
 * <p>Opções em {@code KC_SPI_AUTHENTICATOR__LEGACY_RESET_CHOOSE_USER__*} (ver {@link LegacyClientSettings}).
 * Configuração ausente ou inválida: falha fechada, nada é criado e o passo age como o padrão (um erro no log, sem
 * segredo).</p>
 */
public final class LegacyResetChooseUserFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "legacy-reset-choose-user";

    private static final Logger LOG = Logger.getLogger(LegacyResetChooseUserFactory.class);
    // O authenticator padrão também é a sua factory: reaproveitamos os metadados dele (categoria, requisitos).
    private static final ResetCredentialChooseUser BASE = new ResetCredentialChooseUser();

    private LegacyUserClient client;

    @Override
    public Authenticator create(KeycloakSession session) {
        return new LegacyResetChooseUser(client);
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
        return "Legacy Choose User";
    }

    @Override
    public String getReferenceCategory() {
        return BASE.getReferenceCategory();
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return BASE.getRequirementChoices();
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Chooses the user for a password reset, preparing users that exist only in the Legado.";
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
