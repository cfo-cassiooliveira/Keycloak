# ADR-0006: Migração sob demanda dos usuários do Legado por Authenticator + API do Legado

- **Status**: Aceito (2026-11-17)
- **Data**: 2026-11-13
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. III.4, Art. IV.2, Art. IV.5, Art. VII.1, Art. VII.2

## Contexto

Os usuários do `legado` (ASP.NET Core Identity) precisam passar a ser geridos pelo Keycloak sem reset de senha
obrigatório e sem migração em lote (spec 001 do `cadastro`). Regras: o Keycloak é consultado primeiro; se o usuário
não existe, o Legado decide se as credenciais são válidas; só com credenciais válidas o usuário é criado no Keycloak.
O mesmo vale para "Esqueci minha senha", onde o usuário só do Legado é criado sem senha e recebe o link padrão.

## Decisão

1. Dois **Authenticators** próprios substituem os passos padrão em cópias dos fluxos:
   `legacy-username-password-form` (estende `UsernamePasswordForm`) no fluxo `workspace-browser` e
   `legacy-reset-choose-user` (estende `ResetCredentialChooseUser`) no `workspace-reset-credentials`.
2. O Keycloak consulta o Legado **somente pela API** (`https://legado-api:8443/api/v1/internal/...`), por **TLS**
   confiando só na CA do Legado e com requisição **assinada por HMAC-SHA256** usando a chave que o Legado emitiu para o
   Keycloak (ADR-0005 do legado). **O Legado valida a senha**; o hash nunca sai dele. O Legado não conhece o Keycloak:
   não existe client do Legado no realm.
3. Na resposta "válido", a senha digitada vira credencial do Keycloak no algoritmo do realm. Senha fora da política
   do realm é gravada mesmo assim (o Legado já a validou): o hash é criado direto com o algoritmo do realm e o usuário
   recebe a ação obrigatória `UPDATE_PASSWORD` no mesmo login.
4. Qualquer outro resultado, timeout (2 s, sem retry) ou erro → "Usuário ou senha inválidos." e evento de log.
5. O usuário é criado numa **transação própria já confirmada** antes de o login seguir. Em corrida (dois logins
   simultâneos do mesmo usuário), a violação de unicidade desfaz só a transação do perdedor, que relê o usuário criado
   pelo vencedor e segue o fluxo normal.
6. Dado do Legado que o User Profile do realm recusa → nada é criado, o login é negado e o evento
   `LEGACY_UNAVAILABLE` leva `error=invalid_profile` (o log traz só os nomes dos atributos, nunca os valores).
7. O Legado é marcado como migrado **logo após o commit** dessa transação interna, pela própria
   `LegacyMigrationService` (chamada idempotente, nunca lança exceção): a marcação não depende de o login concluir
   (OTP ou `UPDATE_PASSWORD` abandonados não a impedem). O event listener `legacy-migration-completion` cobre os
   demais caminhos em que a senha passa a existir: `RESET_PASSWORD`, `UPDATE_PASSWORD` e `UPDATE_CREDENTIAL` (chamada
   depois do commit da transação principal, nunca em rollback). Falha da marcação gera
   `LEGACY_MIGRATION_MARK_FAILED`, sem quebrar o fluxo.
8. A marcação é **síncrona** na requisição de login (e na do reset): no pior caso soma até 2 s à resposta.
9. O OTP é obrigatório também para `admin` (RN-12), além de `log-investigator`, nos fluxos `workspace-browser` (login)
   e `workspace-reset-credentials` (reset): a conta migrada de um administrador do Legado configura o autenticador
   antes de concluir.
10. Os eventos de migração levam o `client_id` do client OIDC em curso. O `trace_id` só aparece quando o tracing
    (OpenTelemetry) do Keycloak está ligado.

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| User Storage Federation com import | Padrão conhecido; usuários visíveis no Admin Console | Importa o usuário na consulta, mesmo com senha errada; vínculo permanente com o provider | Contraria a regra "só migra se a senha conferir" |
| JDBC direto no SQL Server do Legado | Sem mudar o Legado | Banco compartilhado; SPI reimplementa o hash do Identity | Viola o Art. III.4 |
| SPI verifica o hash (Legado devolve o hash) | Legado mais simples | Hash trafega e sai do bounded context; criptografia duplicada | Decisão do responsável: o Legado valida |
| Migração em lote com import de hash | Desliga o Legado mais cedo | Exige janela e leva contas inativas | Fora do escopo desta fase |
| Token client credentials do Keycloak para chamar o Legado | Sem segredo novo | O Legado passaria a depender do Keycloak (emissor/JWKS) | O Legado precisa ser independente |
| Login e senha em Base64 | Simples | Codificação reversível, não protege nada | Falsa sensação de segurança (OWASP A04) |

## Consequências

- **Positivas**: migração transparente; o Legado mantém o lockout e a decisão da senha; usuários já migrados não
  têm custo extra no login.
- **Negativas / riscos**: dependência de classes internas do Keycloak (upgrade sempre em PR próprio com os `*IT`);
  contas só do Legado não passam pelo brute force do Keycloak (cobertas pelo lockout do Legado e por alerta).
- **Riscos aceitos**:
  - **Enumeração por tempo**: login ou reset de usuário só do Legado ou inexistente faz uma chamada HTTP ao Legado
    (até 2 s), e o usuário nativo do Keycloak não faz. A diferença de tempo permite distinguir os casos. Mitigação
    futura: rate limit e/ou atraso uniforme; hoje o alerta `Identidade - Tentativas de migração inválidas` do Graylog
    vigia o volume.
  - **Sem rate limit no Keycloak para contas só do Legado**: o lockout dessas contas é do Legado (5 falhas), porque o
    brute force do Keycloak só vale para usuário que já existe nele.
  - **Marcação síncrona** na requisição de login e de reset (até 2 s, decisão 8).
- **Ações decorrentes** (feitas): fluxos `workspace-browser` e `workspace-reset-credentials` no realm e no
  `provision.sh` (`ensure_flow`); ADR-0007 (log mascarado); ADR-0005 do Legado (aceito).
