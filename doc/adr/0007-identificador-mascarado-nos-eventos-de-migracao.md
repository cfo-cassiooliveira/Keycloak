# ADR-0007: Identificador mascarado nos eventos de migração (exceção ao ADR-0002)

- **Status**: Proposto
- **Data**: 2026-11-13
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. IV.1 (LGPD), Art. IV.6, Art. VI.1

## Contexto

O ADR-0002 proíbe username, e-mail, IP e sessão nos registros de auditoria; usuários aparecem só pelo id técnico.
Nas tentativas de login ou reset de quem **não existe** no Keycloak não há id técnico, e a operação precisa rastrear
essas tentativas (ex.: muitas tentativas contra o mesmo e-mail).

## Decisão

1. Os eventos `LEGACY_USER_NOT_FOUND`, `LEGACY_PASSWORD_MISMATCH`, `LEGACY_ACCOUNT_LOCKED`, `LEGACY_ALREADY_MIGRATED`,
   `RESET_UNKNOWN_USER` e `LEGACY_UNAVAILABLE` podem levar o campo **`masked_login`**. Nenhum outro tipo de evento.
2. Máscara (`MaskedIdentifier`): valor normalizado (trim, minúsculas); até 3 primeiros caracteres (1 se o trecho tiver
   até 3) + `***`; em e-mail, + `@` + primeiro rótulo do domínio. Ex.: `ana.souza@gmail.com` → `ana***@gmail`.
3. Retenção: a do Graylog (30 dias). Finalidade: segurança e prevenção a fraude (legítimo interesse, LGPD art. 7º, IX).
4. Teste automatizado garante que nenhum evento contém e-mail ou login completos.

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| Não logar o identificador | Zero dado pessoal | Impossível rastrear ataque a uma conta | Requisito explícito de rastreio |
| Hash (HMAC) do identificador | Correlaciona sem revelar | Operação não consegue reconhecer o alvo; chave a gerir | Menos útil para a operação |
| E-mail completo | Rastreio total | Viola minimização (LGPD) e o ADR-0002 | Rejeitado |

## Consequências

- **Positivas**: rastreio de tentativas sem expor o identificador completo.
- **Negativas / riscos**: dado pseudonimizado ainda é dado pessoal; exige allowlist por tipo de evento.
- **Ações decorrentes**: alterar `AuditEventMapper`/`AuditRecord`; atualizar a regra "Auditoria só com campos da
  allowlist" no `CLAUDE.md` do keycloak com a referência a este ADR.
