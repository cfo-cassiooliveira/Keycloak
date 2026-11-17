package com.workspace.keycloak.legacy;

/**
 * Motivo curto de uma chamada ao Legado ter falhado. O {@link #code()} vai no campo {@code error} do evento
 * LEGACY_UNAVAILABLE, então nunca carrega texto livre, login nem senha.
 */
public enum FailureReason {
    /** Estourou o tempo total da chamada. */
    TIMEOUT("timeout"),
    /** Não conectou ou a conexão caiu. */
    CONNECTION("connection"),
    /** Falha de TLS (inclusive certificado de outra CA). */
    TLS("tls"),
    /** Legado respondeu 401 (assinatura recusada). */
    HTTP_401("http_401"),
    /** Legado respondeu 5xx. */
    HTTP_5XX("http_5xx"),
    /** Qualquer outro status inesperado. */
    HTTP_ERROR("http_error"),
    /** Corpo fora do contrato. */
    INVALID_RESPONSE("invalid_response");

    private final String code;

    FailureReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
