package com.workspace.keycloak.legacy;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.keycloak.common.util.Time;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.policy.PasswordPolicyManagerProvider;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.userprofile.UserProfileProvider;
import org.keycloak.userprofile.ValidationException;

/**
 * Implementação de {@link MigrationUserStore} sobre a sessão do Keycloak.
 *
 * <p>A gravação roda numa <b>transação própria</b>, confirmada antes de voltar ao login: se dois logins simultâneos
 * tentarem criar o mesmo usuário, a violação de unicidade só desfaz a transação do perdedor (a do login em andamento
 * não fica marcada para rollback) e ele pode reler o usuário criado pelo vencedor. O User Profile do realm valida todos
 * os dados do Legado; o hash da senha usa o algoritmo do realm.</p>
 */
public final class KeycloakMigrationUserStore implements MigrationUserStore {

    private static final String USERNAME_EXISTS = "usernameExistsMessage";
    private static final String EMAIL_EXISTS = "emailExistsMessage";
    private static final String TASK_NAME = "legacy-user-migration";

    private final KeycloakSession session;

    public KeycloakMigrationUserStore(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Optional<ExistingUser> find(String login) {
        RealmModel realm = session.getContext().getRealm();
        UserModel user;
        try {
            user = KeycloakModelUtils.findUserByNameOrEmail(session, realm, login);
        } catch (ModelDuplicateException e) {
            // Dado duplicado no Keycloak: o formulário padrão trata e mostra o erro; aqui só impedimos a migração.
            return Optional.of(new ExistingUser(null, true, null));
        }
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(new ExistingUser(user.getId(),
                user.credentialManager().isConfiguredFor(PasswordCredentialModel.TYPE),
                user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID)));
    }

    @Override
    public StoredUser createOrComplete(LegacyUser legacyUser, String existingUserId, String password) {
        return run(legacyUser, existingUserId, password);
    }

    @Override
    public StoredUser createWithoutPassword(LegacyUser legacyUser) {
        return run(legacyUser, null, null);
    }

    private StoredUser run(LegacyUser legacyUser, String existingUserId, String password) {
        StoredUser stored = KeycloakModelUtils.runJobInTransactionWithResult(session.getKeycloakSessionFactory(),
                session.getContext(), inner -> write(inner, legacyUser, existingUserId, password), TASK_NAME);
        evictFromCache(stored.userId());
        return stored;
    }

    // A sessão do login já leu o usuário (conta "casca") antes da gravação; sem invalidar, ela validaria a senha contra
    // a cópia antiga, sem a credencial recém-criada.
    private void evictFromCache(String userId) {
        UserCache cache = session.getProvider(UserCache.class);
        RealmModel realm = session.getContext().getRealm();
        UserModel user = cache == null ? null : session.users().getUserById(realm, userId);
        if (user != null) {
            cache.evict(realm, user);
        }
    }

    private StoredUser write(KeycloakSession inner, LegacyUser legacyUser, String existingUserId, String password) {
        RealmModel realm = inner.getContext().getRealm();
        UserModel user = saveProfile(inner, realm, legacyUser, existingUserId);
        // Sem o User Profile do workspace (provision.sh) o Keycloak descarta em silêncio os atributos próprios; sem o vínculo
        // não há como marcar o Legado nem completar a conta depois, então a transação é desfeita.
        if (user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID) == null) {
            throw new InvalidProfileException(Set.of(LegacyUserMapper.ATTR_LEGACY_USER_ID));
        }
        user.setEnabled(true);
        user.setEmailVerified(legacyUser.emailConfirmed());
        for (String roleName : LegacyUserMapper.toRealmRoles(legacyUser)) {
            RoleModel role = realm.getRole(roleName);
            if (role == null) {
                throw new IllegalStateException("Realm role is missing: " + roleName);
            }
            user.grantRole(role);
        }
        // Sem senha (reset): o usuário nasce "casca" e define a senha pelo link do e-mail.
        boolean passwordUpdateRequired = password != null && storePassword(inner, realm, user, password);
        return new StoredUser(user.getId(), passwordUpdateRequired);
    }

    private UserModel saveProfile(KeycloakSession inner, RealmModel realm, LegacyUser legacyUser,
            String existingUserId) {
        UserProfileProvider profiles = inner.getProvider(UserProfileProvider.class);
        Map<String, List<String>> attributes = LegacyUserMapper.toAttributes(legacyUser);
        try {
            if (existingUserId == null) {
                return profiles.create(UserProfileContext.USER_API, attributes).create();
            }
            UserModel shell = inner.users().getUserById(realm, existingUserId);
            if (shell == null) {
                throw new DuplicateUserException();
            }
            profiles.create(UserProfileContext.USER_API, attributes, shell).update(false);
            return shell;
        } catch (ValidationException e) {
            if (e.hasError(USERNAME_EXISTS, EMAIL_EXISTS)) {
                throw new DuplicateUserException();
            }
            Set<String> names = e.getErrors().stream().map(ValidationException.Error::getAttribute)
                    .collect(Collectors.toSet());
            throw new InvalidProfileException(names);
        } catch (ModelDuplicateException e) {
            throw new DuplicateUserException();
        }
    }

    // Mesma escolha do Keycloak: algoritmo da política do realm; sem ele (ou se não existir), o padrão do servidor.
    private static PasswordHashProvider hashProvider(KeycloakSession inner, PasswordPolicy policy) {
        if (policy.getHashAlgorithm() != null) {
            PasswordHashProvider provider = inner.getProvider(PasswordHashProvider.class, policy.getHashAlgorithm());
            if (provider != null) {
                return provider;
            }
        }
        return inner.getProvider(PasswordHashProvider.class);
    }

    // Retorna true quando a senha não atende à política: ela é gravada mesmo assim (o Legado a validou) e o usuário
    // recebe UPDATE_PASSWORD (RN-04). Dentro da política, usa o caminho normal do Keycloak.
    private boolean storePassword(KeycloakSession inner, RealmModel realm, UserModel user, String password) {
        if (inner.getProvider(PasswordPolicyManagerProvider.class).validate(realm, user, password) == null) {
            user.credentialManager().updateCredential(UserCredentialModel.password(password));
            return false;
        }
        PasswordPolicy policy = realm.getPasswordPolicy();
        PasswordHashProvider hash = hashProvider(inner, policy);
        PasswordCredentialModel credential = hash.encodedCredential(password, policy.getHashIterations());
        credential.setCreatedDate(Time.currentTimeMillis());
        user.credentialManager().createStoredCredential(credential);
        user.addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        return true;
    }
}
