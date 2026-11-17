package com.workspace.keycloak.legacy;

import java.util.List;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/**
 * Criação do {@link LegacyUserClient} e metadados de configuração compartilhados pelos providers que falam com o
 * Legado (formulário de login, escolha de usuário do reset e listener de conclusão). Cada provider lê as mesmas opções
 * do próprio escopo ({@code KC_SPI_<TIPO>__<ID>__<OPÇÃO>}).
 */
final class LegacyClientConfigSupport {

    private LegacyClientConfigSupport() {
    }

    /**
     * Cria o cliente a partir da configuração do provider. Configuração ausente ou inválida: falha fechada, devolve
     * {@code null} e registra um erro (a mensagem de {@link LegacyClientSettings} nunca ecoa a chave).
     */
    static LegacyUserClient createClient(Config.Scope config, String providerId, Logger log) {
        try {
            return new HttpLegacyUserClient(LegacyClientSettings.fromConfig(config));
        } catch (RuntimeException e) {
            log.errorf("Legacy migration disabled: invalid or missing configuration of %s (%s)", providerId,
                    e.getMessage());
            return null;
        }
    }

    static List<ProviderConfigProperty> configMetadata() {
        return ProviderConfigurationBuilder.create()
                .property().name(LegacyClientSettings.OPTION_BASE_URL).type(ProviderConfigProperty.STRING_TYPE)
                .helpText("Base URL of the Legado internal API (e.g. https://legado-api:8443).").add()
                .property().name(LegacyClientSettings.OPTION_TIMEOUT_MS).type(ProviderConfigProperty.INTEGER_TYPE)
                .defaultValue(LegacyClientSettings.DEFAULT_TIMEOUT_MS).helpText("Total timeout per call, in ms.").add()
                .property().name(LegacyClientSettings.OPTION_KEY_ID).type(ProviderConfigProperty.STRING_TYPE)
                .helpText("Identifier of the HMAC key issued by the Legado.").add()
                .property().name(LegacyClientSettings.OPTION_SIGNING_KEY).type(ProviderConfigProperty.STRING_TYPE)
                .secret(true).helpText("HMAC key (256 bits, Base64).").add()
                .property().name(LegacyClientSettings.OPTION_CA_FILE).type(ProviderConfigProperty.STRING_TYPE)
                .helpText("PEM file with the Legado CA, the only one trusted.").add()
                .build();
    }
}
