package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTaskWithResult;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.policy.PasswordPolicyManagerProvider;
import org.keycloak.policy.PolicyError;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.userprofile.UserProfileProvider;
import org.keycloak.userprofile.ValidationException;
import org.keycloak.validate.ValidationError;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.workspace.keycloak.legacy.MigrationUserStore.DuplicateUserException;
import com.workspace.keycloak.legacy.MigrationUserStore.ExistingUser;
import com.workspace.keycloak.legacy.MigrationUserStore.InvalidProfileException;
import com.workspace.keycloak.legacy.MigrationUserStore.StoredUser;

/** Testes unitários do store: a sessão do Keycloak é simulada; o fluxo real é coberto pelos *IT. */
class KeycloakMigrationUserStoreTest {

    private static final UUID LEGACY_ID = UUID.fromString("0b2f4c1e-8a3d-4e5f-9a6b-7c8d9e0f1a2b");
    private static final String USER_ID = "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b";

    private final KeycloakSession session = mock(KeycloakSession.class);
    private final KeycloakContext context = mock(KeycloakContext.class);
    private final RealmModel realm = mock(RealmModel.class);
    private final UserProvider users = mock(UserProvider.class);
    private final UserModel user = mock(UserModel.class);
    private final SubjectCredentialManager credentials = mock(SubjectCredentialManager.class);
    private final UserProfileProvider profiles = mock(UserProfileProvider.class);
    private final UserProfile profile = mock(UserProfile.class);
    private final PasswordPolicyManagerProvider policyManager = mock(PasswordPolicyManagerProvider.class);
    private final PasswordPolicy policy = mock(PasswordPolicy.class);
    private final PasswordHashProvider defaultHash = mock(PasswordHashProvider.class);
    private final UserCache cache = mock(UserCache.class);
    private final RoleModel userRole = mock(RoleModel.class);
    private final RoleModel adminRole = mock(RoleModel.class);
    private MockedStatic<KeycloakModelUtils> utils;
    private final KeycloakMigrationUserStore store = new KeycloakMigrationUserStore(session);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(session.getKeycloakSessionFactory()).thenReturn(mock(KeycloakSessionFactory.class));
        when(session.users()).thenReturn(users);
        when(session.getProvider(UserProfileProvider.class)).thenReturn(profiles);
        when(session.getProvider(PasswordPolicyManagerProvider.class)).thenReturn(policyManager);
        when(session.getProvider(PasswordHashProvider.class)).thenReturn(defaultHash);
        when(session.getProvider(UserCache.class)).thenReturn(cache);
        when(realm.getPasswordPolicy()).thenReturn(policy);
        when(realm.getRole("user")).thenReturn(userRole);
        when(realm.getRole("admin")).thenReturn(adminRole);
        when(user.getId()).thenReturn(USER_ID);
        when(user.credentialManager()).thenReturn(credentials);
        when(users.getUserById(realm, USER_ID)).thenReturn(user);
        utils = Mockito.mockStatic(KeycloakModelUtils.class);
        // A "transação interna" roda a tarefa na mesma sessão simulada.
        utils.when(() -> KeycloakModelUtils.runJobInTransactionWithResult(any(KeycloakSessionFactory.class),
                any(KeycloakContext.class), any(KeycloakSessionTaskWithResult.class), anyString()))
                .thenAnswer(invocation -> ((KeycloakSessionTaskWithResult<Object>) invocation.getArgument(2))
                        .run(session));
    }

    @AfterEach
    void tearDown() {
        utils.close();
    }

    private static LegacyUser legacyUser(List<String> roles) {
        return new LegacyUser(LEGACY_ID, "ana", "ana@gmail.com", true, "Ana Souza", null, null, roles);
    }

    private void givenProfileCreatesUser() {
        when(profiles.create(eq(UserProfileContext.USER_API), any(Map.class))).thenReturn(profile);
        when(profile.create()).thenReturn(user);
        when(user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID)).thenReturn(LEGACY_ID.toString());
    }

    private static ValidationException validationError(String message, String attribute) {
        return new ValidationException(new ValidationError("test", attribute, message));
    }

    @Test
    void find_WhenUserDoesNotExist_ReturnsEmpty() {
        utils.when(() -> KeycloakModelUtils.findUserByNameOrEmail(session, realm, "ana")).thenReturn(null);

        assertThat(store.find("ana")).isEmpty();
    }

    @Test
    void find_WhenUserExists_ReturnsIdPasswordFlagAndLegacyLink() {
        utils.when(() -> KeycloakModelUtils.findUserByNameOrEmail(session, realm, "ana")).thenReturn(user);
        when(credentials.isConfiguredFor(PasswordCredentialModel.TYPE)).thenReturn(true);
        when(user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID)).thenReturn(LEGACY_ID.toString());

        assertThat(store.find("ana")).contains(new ExistingUser(USER_ID, true, LEGACY_ID.toString()));
    }

    @Test
    void find_WhenShellUserWithoutPassword_FlagsNoPasswordAndNoLink() {
        utils.when(() -> KeycloakModelUtils.findUserByNameOrEmail(session, realm, "ana")).thenReturn(user);
        when(credentials.isConfiguredFor(PasswordCredentialModel.TYPE)).thenReturn(false);

        assertThat(store.find("ana")).contains(new ExistingUser(USER_ID, false, null));
    }

    @Test
    void find_WhenKeycloakHasDuplicates_BlocksMigrationByReportingExistingWithPassword() {
        utils.when(() -> KeycloakModelUtils.findUserByNameOrEmail(session, realm, "ana"))
                .thenThrow(new ModelDuplicateException("dup"));

        assertThat(store.find("ana")).contains(new ExistingUser(null, true, null));
    }

    @Test
    void createWithoutPassword_CreatesEnabledShellWithRolesAndNoCredential() {
        givenProfileCreatesUser();

        StoredUser stored = store.createWithoutPassword(legacyUser(List.of("Admin")));

        assertThat(stored).isEqualTo(new StoredUser(USER_ID, false));
        verify(user).setEnabled(true);
        verify(user).setEmailVerified(true);
        verify(user).grantRole(userRole);
        verify(user).grantRole(adminRole);
        verify(credentials, never()).updateCredential(any());
        verify(credentials, never()).createStoredCredential(any());
        verify(cache).evict(realm, user);
    }

    @Test
    void createOrComplete_WhenPasswordMeetsPolicy_UsesStandardCredentialUpdate() {
        givenProfileCreatesUser();
        when(policyManager.validate(realm, user, "Secret#123")).thenReturn(null);

        StoredUser stored = store.createOrComplete(legacyUser(List.of()), null, "Secret#123");

        assertThat(stored.passwordUpdateRequired()).isFalse();
        verify(credentials).updateCredential(any(UserCredentialModel.class));
        verify(user, never()).addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        verify(user, never()).grantRole(adminRole);
    }

    @Test
    void createOrComplete_WhenPasswordViolatesPolicy_StoresItAndRequiresUpdate() {
        givenProfileCreatesUser();
        when(policyManager.validate(realm, user, "weak")).thenReturn(new PolicyError("invalidPasswordMinLengthMessage"));
        when(policy.getHashAlgorithm()).thenReturn(null);
        when(policy.getHashIterations()).thenReturn(5);
        PasswordCredentialModel credential = mock(PasswordCredentialModel.class);
        when(defaultHash.encodedCredential("weak", 5)).thenReturn(credential);

        StoredUser stored = store.createOrComplete(legacyUser(List.of()), null, "weak");

        assertThat(stored.passwordUpdateRequired()).isTrue();
        verify(credentials).createStoredCredential(credential);
        verify(user).addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
    }

    @Test
    void createOrComplete_WhenRealmAlgorithmIsAvailable_UsesItToHashTheWeakPassword() {
        givenProfileCreatesUser();
        when(policyManager.validate(realm, user, "weak")).thenReturn(new PolicyError("invalidPassword"));
        when(policy.getHashAlgorithm()).thenReturn("pbkdf2-sha512");
        when(policy.getHashIterations()).thenReturn(7);
        PasswordHashProvider realmHash = mock(PasswordHashProvider.class);
        PasswordCredentialModel credential = mock(PasswordCredentialModel.class);
        when(session.getProvider(PasswordHashProvider.class, "pbkdf2-sha512")).thenReturn(realmHash);
        when(realmHash.encodedCredential("weak", 7)).thenReturn(credential);

        store.createOrComplete(legacyUser(List.of()), null, "weak");

        verify(credentials).createStoredCredential(credential);
        verify(defaultHash, never()).encodedCredential(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void createOrComplete_WhenRealmAlgorithmIsUnknown_FallsBackToServerDefault() {
        givenProfileCreatesUser();
        when(policyManager.validate(realm, user, "weak")).thenReturn(new PolicyError("invalidPassword"));
        when(policy.getHashAlgorithm()).thenReturn("gone");
        when(policy.getHashIterations()).thenReturn(3);
        PasswordCredentialModel credential = mock(PasswordCredentialModel.class);
        when(session.getProvider(PasswordHashProvider.class, "gone")).thenReturn(null);
        when(defaultHash.encodedCredential("weak", 3)).thenReturn(credential);

        store.createOrComplete(legacyUser(List.of()), null, "weak");

        verify(credentials).createStoredCredential(credential);
    }

    @Test
    void createOrComplete_WhenShellExists_UpdatesItInsteadOfCreating() {
        when(profiles.create(eq(UserProfileContext.USER_API), any(Map.class), eq(user))).thenReturn(profile);
        when(user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID)).thenReturn(LEGACY_ID.toString());
        when(policyManager.validate(realm, user, "Secret#123")).thenReturn(null);

        StoredUser stored = store.createOrComplete(legacyUser(List.of()), USER_ID, "Secret#123");

        assertThat(stored.userId()).isEqualTo(USER_ID);
        verify(profile).update(false);
        verify(profile, never()).create();
    }

    @Test
    void createOrComplete_WhenShellVanished_ThrowsDuplicate() {
        when(users.getUserById(realm, "gone")).thenReturn(null);

        assertThatThrownBy(() -> store.createOrComplete(legacyUser(List.of()), "gone", "x"))
                .isInstanceOf(DuplicateUserException.class);
    }

    @Test
    void createWithoutPassword_WhenUsernameAlreadyTaken_ThrowsDuplicate() {
        when(profiles.create(eq(UserProfileContext.USER_API), any(Map.class))).thenReturn(profile);
        when(profile.create()).thenThrow(validationError("usernameExistsMessage", "username"));

        assertThatThrownBy(() -> store.createWithoutPassword(legacyUser(List.of())))
                .isInstanceOf(DuplicateUserException.class);
    }

    @Test
    void createWithoutPassword_WhenEmailAlreadyTaken_ThrowsDuplicate() {
        when(profiles.create(eq(UserProfileContext.USER_API), any(Map.class))).thenReturn(profile);
        when(profile.create()).thenThrow(validationError("emailExistsMessage", "email"));

        assertThatThrownBy(() -> store.createWithoutPassword(legacyUser(List.of())))
                .isInstanceOf(DuplicateUserException.class);
    }

    @Test
    void createWithoutPassword_WhenDatabaseRejectsDuplicate_ThrowsDuplicate() {
        when(profiles.create(eq(UserProfileContext.USER_API), any(Map.class))).thenReturn(profile);
        when(profile.create()).thenThrow(new ModelDuplicateException("dup"));

        assertThatThrownBy(() -> store.createWithoutPassword(legacyUser(List.of())))
                .isInstanceOf(DuplicateUserException.class);
    }

    @Test
    void createWithoutPassword_WhenProfileRejectsAttribute_ThrowsInvalidProfileWithAttributeNames() {
        when(profiles.create(eq(UserProfileContext.USER_API), any(Map.class))).thenReturn(profile);
        when(profile.create()).thenThrow(validationError("invalidPhone", "mobilePhone"));

        assertThatThrownBy(() -> store.createWithoutPassword(legacyUser(List.of())))
                .isInstanceOfSatisfying(InvalidProfileException.class,
                        e -> assertThat(e.attributes()).containsExactly("mobilePhone"));
    }

    @Test
    void createWithoutPassword_WhenLegacyLinkWasDiscarded_ThrowsInvalidProfile() {
        when(profiles.create(eq(UserProfileContext.USER_API), any(Map.class))).thenReturn(profile);
        when(profile.create()).thenReturn(user);
        when(user.getFirstAttribute(LegacyUserMapper.ATTR_LEGACY_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> store.createWithoutPassword(legacyUser(List.of())))
                .isInstanceOfSatisfying(InvalidProfileException.class,
                        e -> assertThat(e.attributes()).containsExactly(LegacyUserMapper.ATTR_LEGACY_USER_ID));
        verify(user, never()).setEnabled(true);
    }

    @Test
    void createWithoutPassword_WhenRealmRoleIsMissing_ThrowsIllegalState() {
        givenProfileCreatesUser();
        when(realm.getRole("user")).thenReturn(null);

        assertThatThrownBy(() -> store.createWithoutPassword(legacyUser(List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("user");
    }

    @Test
    void createWithoutPassword_WhenNoUserCacheAvailable_SkipsEviction() {
        givenProfileCreatesUser();
        when(session.getProvider(UserCache.class)).thenReturn(null);

        assertThat(store.createWithoutPassword(legacyUser(List.of())).userId()).isEqualTo(USER_ID);
        verify(cache, never()).evict(any(RealmModel.class), any(UserModel.class));
    }
}
