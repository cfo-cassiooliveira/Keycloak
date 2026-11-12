package com.workspace.keycloak.audit;

/** Destino dos registros de auditoria (console ou Graylog com fallback local). */
public interface AuditSink extends AutoCloseable {

    /** Entrega o registro; nunca deve bloquear o fluxo de autenticação por I/O remoto. */
    void write(AuditRecord auditRecord);

    /** Libera recursos; registros ainda pendentes não podem ser perdidos. */
    @Override
    default void close() {
        // Destinos síncronos não têm nada pendente.
    }
}
