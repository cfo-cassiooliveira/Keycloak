package com.workspace.keycloak.audit;

/** Nível de log de um registro de auditoria (RN-02 da Spec 001). */
public enum AuditLevel {
    /** Operação concluída como esperado. */
    INFO,
    /** Falha esperada de autenticação ou de operação administrativa. */
    WARN
}
