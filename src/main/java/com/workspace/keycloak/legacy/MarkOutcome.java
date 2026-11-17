package com.workspace.keycloak.legacy;

/** Resultado da marcação de migração no Legado. */
public sealed interface MarkOutcome {

    /** 204: marcado (também quando já estava marcado com o mesmo usuário do Keycloak). */
    record Marked() implements MarkOutcome {
    }

    /** 409: o Legado já está marcado com outro usuário do Keycloak. */
    record Conflict() implements MarkOutcome {
    }

    /** Timeout, erro HTTP, certificado inválido ou resposta fora do contrato. */
    record Unavailable(FailureReason reason) implements MarkOutcome {
    }
}
