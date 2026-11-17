package com.workspace.keycloak.legacy;

/**
 * Endereço de um usuário do Legado. Dado pessoal: {@link #toString()} não exibe nenhum campo.
 */
public record LegacyAddress(String postalCode, String street, String number, String complement, String district,
        String city, int cityIbgeCode, String state) {

    @Override
    public String toString() {
        return "LegacyAddress[redacted]";
    }
}
