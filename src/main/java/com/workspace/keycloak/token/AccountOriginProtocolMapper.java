package com.workspace.keycloak.token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.TokenIntrospectionTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import com.workspace.keycloak.legacy.LegacyUserMapper;

/**
 * Claim com a origem da conta (P3 da spec 002 do rabbitmq): {@code imported} (importação em lote, atributo
 * {@code importLineId}) ou {@code migrated} (migração sob demanda do Legado, atributo {@code legacyUserId}). Conta sem
 * nenhum dos dois não recebe o claim.
 *
 * <p>Os dois atributos só são editáveis por administrador ou serviço (User Profile), então o usuário não escolhe a
 * própria origem. O claim leva só a origem, nunca o valor do atributo. É um mapper próprio porque os nativos apenas
 * copiam o valor do atributo (exporiam o id da linha ou do Legado) ou emitem constante sem condição; o script mapper
 * depende de funcionalidade preview.</p>
 */
public class AccountOriginProtocolMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper, TokenIntrospectionTokenMapper {

    private static final String PROVIDER_ID = "workspace-account-origin-mapper";
    private static final String IMPORTED = "imported";
    private static final String MIGRATED = "migrated";
    // Nome do atributo gravado pelo importador do Cadastro (KeycloakUserProvisioner) e declarado no User Profile.
    private static final String ATTR_IMPORT_LINE_ID = "importLineId";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = configProperties();

    private static List<ProviderConfigProperty> configProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>();
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(properties);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(properties, AccountOriginProtocolMapper.class);
        return List.copyOf(properties);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "Workspace account origin";
    }

    @Override
    public String getHelpText() {
        return "Adds the account origin (imported or migrated) derived from admin-only user attributes.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession, ClientSessionContext clientSessionContext) {
        originOf(userSession.getUser())
                .ifPresent(origin -> OIDCAttributeMapperHelper.mapClaim(token, mappingModel, origin));
    }

    // Importada vem primeiro: o importador também marca a conta como migrada no Legado, o que não muda a origem.
    private static Optional<String> originOf(UserModel user) {
        if (hasValue(user, ATTR_IMPORT_LINE_ID)) {
            return Optional.of(IMPORTED);
        }
        return hasValue(user, LegacyUserMapper.ATTR_LEGACY_USER_ID) ? Optional.of(MIGRATED) : Optional.empty();
    }

    private static boolean hasValue(UserModel user, String attribute) {
        String value = user.getFirstAttribute(attribute);
        return value != null && !value.isBlank();
    }
}
