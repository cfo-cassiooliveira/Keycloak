package com.workspace.keycloak.legacy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.keycloak.util.JsonSerialization;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Valida o schema das respostas da API interna do Legado (plan §4). Qualquer desvio (outcome desconhecido, {@code user}
 * ausente quando esperado ou presente quando não, tipo errado) lança {@link IllegalArgumentException}, que o cliente
 * converte em {@code Unavailable}. As mensagens nunca incluem valores do corpo, pois ele traz dado pessoal.
 */
final class LegacyResponseParser {

    /** Converte um corpo de resposta já aceito pelo status em um resultado tipado. */
    @FunctionalInterface
    interface Parser<T> {
        T parse(int status, byte[] body) throws IOException;
    }

    private LegacyResponseParser() {
    }

    static VerifyOutcome parseVerify(int status, byte[] body) throws IOException {
        JsonNode root = readObject(body);
        String outcome = outcome(root);
        return switch (outcome) {
            case "valid" -> new VerifyOutcome.Valid(requiredUser(root));
            case "not_found" -> noUser(root, new VerifyOutcome.NotFound());
            case "invalid_password" -> noUser(root, new VerifyOutcome.InvalidPassword());
            case "locked" -> noUser(root, new VerifyOutcome.Locked());
            case "migrated" -> noUser(root, new VerifyOutcome.Migrated());
            default -> throw new IllegalArgumentException("unknown outcome");
        };
    }

    static LookupOutcome parseLookup(int status, byte[] body) throws IOException {
        JsonNode root = readObject(body);
        String outcome = outcome(root);
        return switch (outcome) {
            case "found" -> new LookupOutcome.Found(requiredUser(root));
            case "not_found" -> noUser(root, new LookupOutcome.NotFound());
            case "migrated" -> noUser(root, new LookupOutcome.Migrated());
            default -> throw new IllegalArgumentException("unknown outcome");
        };
    }

    private static JsonNode readObject(byte[] body) throws IOException {
        JsonNode root = JsonSerialization.readValue(body, JsonNode.class);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("body is not a JSON object");
        }
        return root;
    }

    private static String outcome(JsonNode root) {
        JsonNode outcome = root.get("outcome");
        if (outcome == null || !outcome.isTextual()) {
            throw new IllegalArgumentException("outcome missing");
        }
        return outcome.asText();
    }

    // "user" só existe quando esperado: presente em outro resultado indica contrato quebrado.
    private static <T> T noUser(JsonNode root, T result) {
        JsonNode user = root.get("user");
        if (user != null && !user.isNull()) {
            throw new IllegalArgumentException("unexpected user");
        }
        return result;
    }

    private static LegacyUser requiredUser(JsonNode root) {
        JsonNode user = root.get("user");
        if (user == null || !user.isObject()) {
            throw new IllegalArgumentException("user missing");
        }
        return new LegacyUser(
                UUID.fromString(requiredText(user, "id")),
                requiredText(user, "userName"),
                requiredText(user, "email"),
                user.path("emailConfirmed").asBoolean(false),
                optionalText(user, "fullName"),
                optionalText(user, "mobilePhone"),
                address(user.get("address")),
                roles(user.get("roles")));
    }

    private static LegacyAddress address(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new LegacyAddress(optionalText(node, "postalCode"), optionalText(node, "street"),
                optionalText(node, "number"), optionalText(node, "complement"), optionalText(node, "district"),
                optionalText(node, "city"), node.path("cityIbgeCode").asInt(0), optionalText(node, "state"));
    }

    private static List<String> roles(JsonNode node) {
        List<String> roles = new ArrayList<>();
        if (node == null || node.isNull()) {
            return roles;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("roles is not an array");
        }
        for (JsonNode role : node) {
            if (!role.isTextual()) {
                throw new IllegalArgumentException("role is not text");
            }
            roles.add(role.asText());
        }
        return roles;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " missing");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
