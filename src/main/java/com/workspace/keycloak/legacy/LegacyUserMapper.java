package com.workspace.keycloak.legacy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converte um {@link LegacyUser} nos atributos do User Profile do realm {@code workspace}
 * ({@code provisioning/workspace-user-profile.json}) e nas realm roles (RN-03). Lógica pura, sem sessão do Keycloak.
 *
 * <p>Só normaliza o <b>formato</b> (celular com DDI, CEP só com dígitos, UF em maiúsculas); valor que ainda assim não
 * passa nas validações do User Profile é recusado pela migração, nunca "consertado" aqui.</p>
 */
public final class LegacyUserMapper {

    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_EMAIL = "email";
    public static final String ATTR_FIRST_NAME = "firstName";
    public static final String ATTR_LAST_NAME = "lastName";
    public static final String ATTR_MOBILE_PHONE = "mobilePhone";
    public static final String ATTR_POSTAL_CODE = "postalCode";
    public static final String ATTR_STREET = "street";
    public static final String ATTR_ADDRESS_NUMBER = "addressNumber";
    public static final String ATTR_COMPLEMENT = "complement";
    public static final String ATTR_DISTRICT = "district";
    public static final String ATTR_CITY = "city";
    public static final String ATTR_CITY_IBGE_CODE = "cityIbgeCode";
    public static final String ATTR_STATE = "state";
    public static final String ATTR_LEGACY_USER_ID = "legacyUserId";

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    private static final String LEGACY_ROLE_ADMIN = "admin";
    private static final int PHONE_DIGITS_WITHOUT_COUNTRY = 11;
    private static final int PHONE_DIGITS_WITH_COUNTRY = 13;
    private static final String COUNTRY_CODE = "55";

    private LegacyUserMapper() {
    }

    /** Login como o Keycloak o compara: sem espaços nas pontas e em minúsculas. */
    public static String normalizeLogin(String login) {
        return login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
    }

    /** Atributos do User Profile; valores ausentes ou em branco não entram. */
    public static Map<String, List<String>> toAttributes(LegacyUser user) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        put(attributes, ATTR_USERNAME, normalizeLogin(user.userName()));
        put(attributes, ATTR_EMAIL, normalizeLogin(user.email()));
        splitName(user.fullName(), attributes);
        put(attributes, ATTR_MOBILE_PHONE, normalizePhone(user.mobilePhone()));
        LegacyAddress address = user.address();
        if (address != null) {
            put(attributes, ATTR_POSTAL_CODE, digitsOnly(address.postalCode()));
            put(attributes, ATTR_STREET, address.street());
            put(attributes, ATTR_ADDRESS_NUMBER, address.number());
            put(attributes, ATTR_COMPLEMENT, address.complement());
            put(attributes, ATTR_DISTRICT, address.district());
            put(attributes, ATTR_CITY, address.city());
            put(attributes, ATTR_CITY_IBGE_CODE,
                    address.cityIbgeCode() > 0 ? "%07d".formatted(address.cityIbgeCode()) : null);
            put(attributes, ATTR_STATE, address.state() == null ? null : address.state().strip().toUpperCase(Locale.ROOT));
        }
        put(attributes, ATTR_LEGACY_USER_ID, user.id() == null ? null : user.id().toString());
        return attributes;
    }

    /** {@code Admin} vira {@code admin} + {@code user}; qualquer outro usuário recebe só {@code user}. */
    public static Set<String> toRealmRoles(LegacyUser user) {
        Set<String> roles = new LinkedHashSet<>();
        roles.add(ROLE_USER);
        boolean admin = user.roles().stream().anyMatch(role -> LEGACY_ROLE_ADMIN.equalsIgnoreCase(role.strip()));
        if (admin) {
            roles.add(ROLE_ADMIN);
        }
        return roles;
    }

    // Primeira palavra = nome; o restante = sobrenome. Nome de uma palavra só não gera sobrenome.
    private static void splitName(String fullName, Map<String, List<String>> attributes) {
        if (fullName == null || fullName.isBlank()) {
            return;
        }
        String[] parts = fullName.strip().split("\\s+", 2);
        put(attributes, ATTR_FIRST_NAME, parts[0]);
        if (parts.length > 1) {
            put(attributes, ATTR_LAST_NAME, parts[1]);
        }
    }

    // O profile exige +55 e 11 dígitos: celular só com DDD (11 dígitos) ou já com 55 (13) é completado; o resto segue
    // como veio para a validação do User Profile recusar.
    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = digitsOnly(phone);
        if (digits.length() == PHONE_DIGITS_WITHOUT_COUNTRY) {
            return "+" + COUNTRY_CODE + digits;
        }
        if (digits.length() == PHONE_DIGITS_WITH_COUNTRY && digits.startsWith(COUNTRY_CODE)) {
            return "+" + digits;
        }
        return phone.strip();
    }

    private static String digitsOnly(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }

    private static void put(Map<String, List<String>> attributes, String name, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(name, List.of(value.strip()));
        }
    }
}
