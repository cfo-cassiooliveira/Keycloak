package com.workspace.keycloak.legacy;

import java.util.Objects;

/** Resultado da consulta de usuário (sem senha) no Legado. Qualquer falha de comunicação é {@link Unavailable}. */
public sealed interface LookupOutcome {

    /** Conta encontrada e ainda não migrada. */
    record Found(LegacyUser user) implements LookupOutcome {
        public Found {
            Objects.requireNonNull(user, "user");
        }
    }

    /** Não existe conta com esse login ou e-mail. */
    record NotFound() implements LookupOutcome {
    }

    /** Conta já migrada para o Keycloak. */
    record Migrated() implements LookupOutcome {
    }

    /** Timeout, erro HTTP, certificado inválido ou resposta fora do contrato. */
    record Unavailable(FailureReason reason) implements LookupOutcome {
    }
}
