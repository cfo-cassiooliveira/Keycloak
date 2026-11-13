# ADR-0006: Migração sob demanda dos usuários do Legado por Authenticator + API do Legado

- **Status**: Proposto
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
   do realm recebe a ação obrigatória `UPDATE_PASSWORD` no mesmo login.
4. Qualquer outro resultado, timeout (2 s, sem retry) ou erro → "Usuário ou senha inválidos." e evento de log.
5. O Legado só é marcado como migrado quando o usuário passa a ter senha no Keycloak, **após o commit**, pelo event
   listener `legacy-migration-completion` (chamada idempotente).

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
- **Ações decorrentes**: fluxos e clients no realm e no `provision.sh`; ADR-0007 (log mascarado); ADR-0005 do Legado.
