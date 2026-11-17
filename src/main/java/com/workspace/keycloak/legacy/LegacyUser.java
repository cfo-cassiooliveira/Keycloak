package com.workspace.keycloak.legacy;

import java.util.List;
import java.util.UUID;

/**
 * Usuário do Legado devolvido pela API interna (contrato {@code LegacyUserDto} do plan §4).
 * Contém dado pessoal: {@link #toString()} mostra só o id técnico.
 *
 * @param address pode ser nulo se o Legado não enviar o endereço
 */
public record LegacyUser(UUID id, String userName, String email, boolean emailConfirmed, String fullName,
        String mobilePhone, LegacyAddress address, List<String> roles) {

    public LegacyUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    @Override
    public String toString() {
        return "LegacyUser[id=" + id + "]";
    }
}
