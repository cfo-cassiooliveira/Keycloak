# ADR-0007: Identificador mascarado nos eventos de migração (exceção ao ADR-0002)

- **Status**: Aceito (2026-11-17)
- **Data**: 2026-11-13
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. IV.1 (LGPD), Art. IV.6, Art. VI.1
- **Relacionados**: [ADR-0002 do graylog](../../../graylog/docs/adr/0002-mascaramento-parcial-lgpd.md) (máscara por nome de campo no pipeline) e [ADR-0003 do graylog](../../../graylog/docs/adr/0003-pseudonimizacao-hmac-pesquisavel.md) (pseudonimização: o Keycloak **não** gera token; a chave HMAC fica só no legado e no cadastro)

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
5. O `masked_login` vai **sempre como campo GELF próprio**, nunca dentro do `short_message`, e só no formato produzido
   por `MaskedIdentifier` (o `AuditEventMapper` descarta qualquer outro valor e o campo em qualquer outro tipo de
   evento). O nome `masked_login` está em `LGPD_LOGIN_FIELDS` do `provision.sh` do Graylog, e o valor, que já chega
   mascarado, passa intacto pelo pipeline. O `ConsoleAuditSink` (sem Graylog) trata o campo da mesma forma.
6. O Keycloak **não** gera `_token` de pseudonimização nem recebe a chave HMAC (ADR-0003 do graylog): a investigação
   por login usa os logs do `legado` e do `cadastro`.
7. Os eventos de migração levam também o `client_id` (id técnico do client OIDC, não é dado pessoal). O `trace_id` e o
   `span_id` só aparecem quando o tracing (OpenTelemetry) do Keycloak está ligado.

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| Não logar o identificador | Zero dado pessoal | Impossível rastrear ataque a uma conta | Requisito explícito de rastreio |
| Hash (HMAC) do identificador | Correlaciona sem revelar | Operação não consegue reconhecer o alvo; chave a gerir | Menos útil para a operação |
| E-mail completo | Rastreio total | Viola minimização (LGPD) e o ADR-0002 | Rejeitado |

## Consequências

- **Positivas**: rastreio de tentativas sem expor o identificador completo.
- **Negativas / riscos**: dado pseudonimizado ainda é dado pessoal; exige allowlist por tipo de evento. Sem token, o
  `masked_login` não permite busca exata por titular no Graylog (serve ao reconhecimento pela operação).
- **Ações decorrentes** (feitas): `AuditEventMapper`, `MigrationAuditEntry` e `MigrationAuditEvent`; regra "Auditoria
  só com campos da allowlist" no `CLAUDE.md` do keycloak com a referência a este ADR; `masked_login` em
  `LGPD_LOGIN_FIELDS` no `provision.sh` do Graylog.
