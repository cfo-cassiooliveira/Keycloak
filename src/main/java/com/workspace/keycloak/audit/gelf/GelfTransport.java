package com.workspace.keycloak.audit.gelf;

import java.io.IOException;

/** Envio de uma mensagem GELF ao Graylog com confirmação de recebimento. */
public interface GelfTransport {

    /**
     * Envia a mensagem e só retorna se o Graylog confirmou o recebimento.
     *
     * @throws IOException se o Graylog estiver indisponível, demorar além do timeout ou recusar a mensagem
     */
    void send(String gelfMessage) throws IOException;

    /** Destino legível para mensagens de erro (sem credenciais). */
    String destination();
}
