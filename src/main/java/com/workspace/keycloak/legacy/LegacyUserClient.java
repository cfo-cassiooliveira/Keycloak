package com.workspace.keycloak.legacy;

import java.util.UUID;

/**
 * Porta para a API interna do Legado (ADR-0006 e ADR-0005 do legado). Nenhum método lança exceção: qualquer falha
 * vira {@code Unavailable}. Login e senha nunca aparecem em mensagens, logs nem {@code toString}.
 */
public interface LegacyUserClient {

    /** {@code POST /api/v1/internal/credential-verifications}: o Legado valida a senha. */
    VerifyOutcome verifyCredentials(String login, String password);

    /** {@code POST /api/v1/internal/user-lookups}: consulta sem senha (fluxo de reset). */
    LookupOutcome lookupUser(String login);

    /** {@code PUT /api/v1/internal/users/{id}/migration}: marca o usuário do Legado como migrado (idempotente). */
    MarkOutcome markMigrated(UUID legacyUserId, UUID keycloakUserId);
}
