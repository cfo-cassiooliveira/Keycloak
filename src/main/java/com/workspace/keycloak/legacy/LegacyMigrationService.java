package com.workspace.keycloak.legacy;

import java.util.Objects;
import java.util.Optional;

import org.jboss.logging.Logger;

import com.workspace.keycloak.audit.MigrationAuditEntry;
import com.workspace.keycloak.audit.MigrationAuditEvent;

/**
 * Regras da migração sob demanda no login (RN-01 a RN-05, ADR-0006). Lógica pura: o Keycloak entra pela porta
 * {@link MigrationUserStore} e o Legado por {@link LegacyUserClient}.
 *
 * <p>O serviço só <b>prepara</b> o usuário: quem autentica continua sendo o formulário padrão do Keycloak, que depois
 * lê a senha recém-gravada. Por isso todo resultado que não migra (senha errada, inexistente, Legado fora) deixa o
 * formulário padrão produzir o erro genérico "Usuário ou senha inválidos.". Falha do Legado ou da gravação vira
 * resultado, não exceção; erro ao ler o próprio Keycloak ({@code store.find}) propaga.</p>
 *
 * <p>Migrar sob demanda dispensa janela de migração, reset em massa e cópia de hashes: o Legado valida a senha, e só
 * então ela vira credencial no Keycloak. Custos (ADR-0006): o Legado precisa ficar de pé enquanto houver conta não
 * migrada, quem nunca entra não migra, e a chamada extra torna o tempo de resposta distinguível.</p>
 */
public final class LegacyMigrationService {

    private static final Logger LOG = Logger.getLogger(LegacyMigrationService.class);
    private static final String FLOW_LOGIN = "login";
    private static final String FLOW_RESET = "reset";
    static final String ERROR_INVALID_PROFILE = "invalid_profile";
    static final String ERROR_MIGRATION_FAILED = "migration_failed";

    /** O que aconteceu na preparação do login. */
    public enum Result {
        /** Usuário do Keycloak com senha (ou sem vínculo com o Legado): o Legado não foi consultado. */
        NATIVE,
        /** Usuário migrado agora; a senha já vale no Keycloak. */
        MIGRATED,
        /** Outro login simultâneo criou o usuário primeiro: segue o fluxo normal. */
        CONCURRENT,
        /** Nada migrado (credenciais não válidas no Legado, Legado fora ou dado recusado). */
        REJECTED
    }

    private final LegacyUserClient client;
    private final MigrationUserStore store;
    private final MigrationAuditPublisher audit;
    private final LegacyMigrationMarker marker;

    public LegacyMigrationService(LegacyUserClient client, MigrationUserStore store, MigrationAuditPublisher audit) {
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.marker = new LegacyMigrationMarker(client, audit);
    }

    /** Migra o usuário do Legado se for o caso; {@code rawLogin} é o que foi digitado (login ou e-mail). */
    public Result prepareLogin(String rawLogin, String password) {
        String login = LegacyUserMapper.normalizeLogin(rawLogin);
        if (login.isEmpty() || password == null || password.isEmpty()) {
            return Result.NATIVE;
        }
        Optional<MigrationUserStore.ExistingUser> existing = store.find(login);
        if (existing.isPresent() && (existing.get().hasPassword() || existing.get().legacyUserId() == null)) {
            return Result.NATIVE;
        }
        String shellId = existing.map(MigrationUserStore.ExistingUser::id).orElse(null);
        return switch (client.verifyCredentials(login, password)) {
            case VerifyOutcome.Valid valid -> migrate(valid.user(), shellId, login, password);
            case VerifyOutcome.NotFound ignored -> reject(MigrationAuditEvent.LEGACY_USER_NOT_FOUND, FLOW_LOGIN, login, null);
            case VerifyOutcome.InvalidPassword ignored ->
                    reject(MigrationAuditEvent.LEGACY_PASSWORD_MISMATCH, FLOW_LOGIN, login, null);
            case VerifyOutcome.Locked ignored -> reject(MigrationAuditEvent.LEGACY_ACCOUNT_LOCKED, FLOW_LOGIN, login, null);
            case VerifyOutcome.Migrated ignored ->
                    reject(MigrationAuditEvent.LEGACY_ALREADY_MIGRATED, FLOW_LOGIN, login, null);
            case VerifyOutcome.Unavailable unavailable ->
                    reject(MigrationAuditEvent.LEGACY_UNAVAILABLE, FLOW_LOGIN, login, unavailable.reason().code());
        };
    }

    /** O que aconteceu na preparação do reset. */
    public enum ResetResult {
        /** Usuário do Keycloak (ou criado por outro pedido simultâneo): o fluxo padrão segue. */
        EXISTING,
        /** Usuário só do Legado, criado agora sem senha: o fluxo segue como usuário existente. */
        PREPARED,
        /** Inexistente, já migrado, Legado fora ou dado recusado: nada criado, nenhum e-mail. */
        UNKNOWN
    }

    /**
     * Prepara o reset de senha (RN-06): usuário só no Legado é criado no Keycloak sem senha. {@code rawLogin} é o que
     * foi digitado (login ou e-mail). O chamador captura falhas e mostra sempre a mesma resposta ao usuário.
     */
    public ResetResult prepareReset(String rawLogin) {
        String login = LegacyUserMapper.normalizeLogin(rawLogin);
        if (login.isEmpty() || store.find(login).isPresent()) {
            return ResetResult.EXISTING;
        }
        return switch (client.lookupUser(login)) {
            case LookupOutcome.Found found -> prepareShell(found.user(), login);
            case LookupOutcome.NotFound ignored -> unknown(MigrationAuditEvent.RESET_UNKNOWN_USER, login, null);
            case LookupOutcome.Migrated ignored -> unknown(MigrationAuditEvent.LEGACY_ALREADY_MIGRATED, login, null);
            case LookupOutcome.Unavailable unavailable ->
                    unknown(MigrationAuditEvent.LEGACY_UNAVAILABLE, login, unavailable.reason().code());
        };
    }

    private ResetResult prepareShell(LegacyUser legacyUser, String login) {
        try {
            MigrationUserStore.StoredUser stored = store.createWithoutPassword(legacyUser);
            audit.publish(MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_USER_PREPARED_FOR_RESET)
                    .withUserId(stored.userId())
                    .withLegacyUserId(String.valueOf(legacyUser.id())));
            return ResetResult.PREPARED;
        } catch (MigrationUserStore.DuplicateUserException e) {
            // Corrida: outro pedido criou o usuário. Relê; se não aparece, é conflito real (e-mail de outra conta).
            if (store.find(login).isPresent()) {
                return ResetResult.EXISTING;
            }
            LOG.error("Legacy user preparation for reset conflicts with an existing Keycloak user");
            return unknown(MigrationAuditEvent.LEGACY_UNAVAILABLE, login, ERROR_MIGRATION_FAILED);
        } catch (MigrationUserStore.InvalidProfileException e) {
            LOG.errorf("Legacy user rejected by the User Profile validation: attributes=%s", e.attributes());
            return unknown(MigrationAuditEvent.LEGACY_UNAVAILABLE, login, ERROR_INVALID_PROFILE);
        } catch (RuntimeException e) {
            LOG.errorf("Legacy user preparation for reset failed: %s", e.getClass().getName());
            return unknown(MigrationAuditEvent.LEGACY_UNAVAILABLE, login, ERROR_MIGRATION_FAILED);
        }
    }

    private ResetResult unknown(MigrationAuditEvent type, String login, String error) {
        reject(type, FLOW_RESET, login, error);
        return ResetResult.UNKNOWN;
    }

    private Result migrate(LegacyUser legacyUser, String shellId, String login, String password) {
        try {
            MigrationUserStore.StoredUser stored = store.createOrComplete(legacyUser, shellId, password);
            audit.publish(MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_USER_MIGRATED)
                    .withUserId(stored.userId())
                    .withLegacyUserId(String.valueOf(legacyUser.id()))
                    .withPasswordUpdateRequired(stored.passwordUpdateRequired())
                    .withFlow(FLOW_LOGIN));
            // RN-07: a senha já está confirmada no Keycloak (a transação interna do store confirmou antes de retornar;
            // em rollback ele lança e este ponto não é alcançado). Marca agora, sem depender de o login concluir
            // (OTP ou UPDATE_PASSWORD abandonado). O marcador nunca lança e o PUT do Legado é idempotente.
            marker.mark(String.valueOf(legacyUser.id()), stored.userId());
            return Result.MIGRATED;
        } catch (MigrationUserStore.DuplicateUserException e) {
            return afterDuplicate(login);
        } catch (MigrationUserStore.InvalidProfileException e) {
            // Dado do Legado fora das regras do User Profile: nada é criado e o login é negado. O log traz só os
            // nomes dos atributos (nunca valores) e o evento leva o login mascarado para a equipe localizar a conta.
            LOG.errorf("Legacy user rejected by the User Profile validation: attributes=%s", e.attributes());
            return reject(MigrationAuditEvent.LEGACY_UNAVAILABLE, FLOW_LOGIN, login, ERROR_INVALID_PROFILE);
        } catch (RuntimeException e) {
            // Só a classe da exceção: a mensagem de erros de banco pode conter login ou e-mail.
            LOG.errorf("Legacy user migration failed: %s", e.getClass().getName());
            return reject(MigrationAuditEvent.LEGACY_UNAVAILABLE, FLOW_LOGIN, login, ERROR_MIGRATION_FAILED);
        }
    }

    // Corrida: outro login criou o usuário. Relê e segue o fluxo normal; se o usuário não aparece, é conflito real
    // (e-mail de outra conta) e a migração é negada.
    private Result afterDuplicate(String login) {
        Optional<MigrationUserStore.ExistingUser> winner = store.find(login);
        if (winner.isPresent() && winner.get().hasPassword()) {
            return Result.CONCURRENT;
        }
        LOG.error("Legacy user migration conflicts with an existing Keycloak user");
        return reject(MigrationAuditEvent.LEGACY_UNAVAILABLE, FLOW_LOGIN, login, ERROR_MIGRATION_FAILED);
    }

    private Result reject(MigrationAuditEvent type, String flow, String login, String error) {
        MigrationAuditEntry entry = MigrationAuditEntry.of(type)
                .withMaskedLogin(MaskedIdentifier.mask(login))
                .withFlow(flow);
        audit.publish(error == null ? entry : entry.withError(error));
        return Result.REJECTED;
    }
}
