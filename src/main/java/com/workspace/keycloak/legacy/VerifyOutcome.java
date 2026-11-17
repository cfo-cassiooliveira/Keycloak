package com.workspace.keycloak.legacy;

import java.util.Objects;

/** Resultado da verificação de credenciais no Legado. Qualquer falha de comunicação é {@link Unavailable}. */
public sealed interface VerifyOutcome {

    /** Credenciais conferem e a conta ainda não foi migrada. */
    record Valid(LegacyUser user) implements VerifyOutcome {
        public Valid {
            Objects.requireNonNull(user, "user");
        }
    }

    /** Não existe conta com esse login ou e-mail. */
    record NotFound() implements VerifyOutcome {
    }

    /** A conta existe e a senha não confere. */
    record InvalidPassword() implements VerifyOutcome {
    }

    /** Conta bloqueada por excesso de tentativas. */
    record Locked() implements VerifyOutcome {
    }

    /** Conta já migrada para o Keycloak. */
    record Migrated() implements VerifyOutcome {
    }

    /** Timeout, erro HTTP, certificado inválido ou resposta fora do contrato. */
    record Unavailable(FailureReason reason) implements VerifyOutcome {
    }
}
