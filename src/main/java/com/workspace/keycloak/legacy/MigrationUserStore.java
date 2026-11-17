package com.workspace.keycloak.legacy;

import java.util.Optional;
import java.util.Set;

/**
 * Porta do {@link LegacyMigrationService} para o cadastro de usuários do Keycloak. A implementação real
 * ({@link KeycloakMigrationUserStore}) usa a sessão do Keycloak; os testes unitários usam um duplo.
 */
public interface MigrationUserStore {

    /**
     * Usuário já existente no Keycloak.
     *
     * @param id id do usuário
     * @param hasPassword se tem credencial de senha
     * @param legacyUserId atributo {@code legacyUserId} (nulo se não veio do Legado)
     */
    record ExistingUser(String id, boolean hasPassword, String legacyUserId) {
    }

    /**
     * Resultado da gravação.
     *
     * @param userId id do usuário no Keycloak
     * @param passwordUpdateRequired {@code true} se a senha não atende à política do realm (UPDATE_PASSWORD)
     */
    record StoredUser(String userId, boolean passwordUpdateRequired) {
    }

    /** Dados do Legado recusados pelo User Profile; carrega só nomes de atributos, nunca valores. */
    final class InvalidProfileException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Set<String> attributes;

        public InvalidProfileException(Set<String> attributes) {
            super("User Profile rejected attributes " + attributes);
            this.attributes = Set.copyOf(attributes);
        }

        public Set<String> attributes() {
            return attributes;
        }
    }

    /** Login ou e-mail já em uso (corrida entre dois logins ou conflito com outro usuário). */
    final class DuplicateUserException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DuplicateUserException() {
            super("User already exists");
        }
    }

    /** Busca por login ou e-mail, já normalizado ({@link LegacyUserMapper#normalizeLogin}). */
    Optional<ExistingUser> find(String login);

    /**
     * Cria o usuário (ou completa a conta "casca" {@code existingUserId}) com os dados do Legado, roles e senha, numa
     * transação própria já confirmada ao retornar.
     *
     * @throws InvalidProfileException se o User Profile recusar algum dado
     * @throws DuplicateUserException se login ou e-mail já existirem
     */
    StoredUser createOrComplete(LegacyUser legacyUser, String existingUserId, String password);

    /**
     * Cria o usuário com os dados do Legado e as roles, <b>sem credencial de senha</b> (fluxo de reset, RN-06), numa
     * transação própria já confirmada ao retornar. A senha só passa a existir quando o usuário a define pelo link.
     *
     * @throws InvalidProfileException se o User Profile recusar algum dado
     * @throws DuplicateUserException se login ou e-mail já existirem
     */
    StoredUser createWithoutPassword(LegacyUser legacyUser);
}
