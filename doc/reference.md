# Referência do Keycloak do Workspace

> Seções movidas **verbatim** do `CLAUDE.md` para reduzir o contexto carregado. Índice: [INDEX.md](INDEX.md).

## Regras detalhadas

- Exceção à regra acima só quando houver ADR **aceito**. O `masked_login` do ADR-0007 (aceito em 2026-11-17) é
  emitido pelos eventos de migração (`MigrationAuditEvent`, via `legacy/MigrationAuditPublisher`), **só** nos seis tipos
  do ADR (`LEGACY_USER_NOT_FOUND`, `LEGACY_PASSWORD_MISMATCH`, `LEGACY_ACCOUNT_LOCKED`, `LEGACY_ALREADY_MIGRATED`,
  `RESET_UNKNOWN_USER`, `LEGACY_UNAVAILABLE`) e só no formato de `MaskedIdentifier`; nos demais tipos o mapper o
  descarta. Ele vai **sempre como campo GELF próprio**, nunca dentro do `short_message`, e o nome
  `masked_login` está em `LGPD_LOGIN_FIELDS` do `../graylog/provisioning/provision.sh` (ADR-0002 do graylog).
  O `ConsoleAuditSink`, usado quando o Graylog não está configurado, também trata esse campo (ADR-0007).
  O `masked_login` vai **sem** `_token`: o Keycloak não gera token de pseudonimização nem recebe a chave HMAC, que fica
  só no legado e no cadastro (ADR-0003 do graylog). Investigação por login usa os logs dessas aplicações.
- `log-investigator` nunca entra em `default-roles-workspace` (só atribuição nominal). O client `cadastro`
  (`fullScopeAllowed=false`) tem a role no escopo, sem mapper novo: o token só ganha `log-investigator` em
  `realm_access` (provado pelo `CadastroRealmIT` e pelo `LogInvestigatorOtpBypassIT`; o smoke confere o escopo).
- **Contrato com o Cadastro (C03)**: a role sozinha não basta, porque SSO por cookie e refresh de token antigo trazem a
  role sem OTP. A policy `logs:investigate` exige a role, `acr=otp` **e** `auth_time` ≤ 5 min; a tela de
  pesquisa (C04) abre `/bff/login?acr_values=otp&returnUrl=/log-search` e repete o pedido quando o `auth_time` vencer; o
  BFF valida `acr` e `auth_time` no callback e a policy os confere a cada chamada (o parâmetro pode ser alterado no
  navegador). `acr=otp` só sai quando o OTP foi digitado **nesta** autenticação:
  step-up por LoA no `workspace-browser` (realm `acr.loa.map` = `{"password":1,"otp":2}`; LoA 2 com
  `loa-max-age=0`), e o `auth_time` vira a hora do step-up. Login comum termina com `acr=password`, mesmo com OTP; o
  reset de senha também, mesmo com `acr_values=otp`. O refresh de token do step-up mantém `acr=otp` e o `auth_time` do
  step-up pela sessão do client (até 10 h): daí o limite de `auth_time` (`LogInvestigatorOtpBypassIT`).
- Client `cadastro-importer` + role `user-importer` (spec 002 do rabbitmq, K0; `ensure_cadastro_importer_client` + `realm-management`
  no JSON): só conta de serviço, `view-users`/`manage-users`, scope `kc-admin-api` opcional, sem realm role no escopo; cria usuário
  sem senha (`UPDATE_PASSWORD` + atributo `importLineId`, editável só por admin/serviço). E-mail de ativação por
  `execute-actions-email` com SMTP do realm no Mailpit e `?lifespan=259200` (72 h) passado por chamada; o lifespan do realm fica no padrão (12 h). `user-importer` nunca
  em `default-roles-workspace`; OTP recente é exigido pelo Cadastro (`acr=otp`). Provado pelo smoke (seção "Importação em lote").
  `KC_CADASTRO_IMPORTER_CLIENT_SECRET` só no `.env`.
- Claim `account_origin` (ADR-0008, importação em lote e migração do Legado): mapper próprio `workspace-account-origin-mapper`
  (`token/AccountOriginProtocolMapper`), configurado **só** no client `cadastro` (JSON + `ensure_cadastro_client`).
  `importLineId` → `imported` (tem precedência: o importador também marca o Legado), senão `legacyUserId` → `migrated`,
  senão sem claim. Os dois atributos precisam continuar `view`/`edit` só `admin` no User Profile e o realm sem
  `unmanagedAttributePolicy`: o `CadastroRealmIT` prova que o próprio usuário não os altera pela Account API.
- Client `grafana` (spec 001 do grafana, ADR-0002; `ensure_grafana_client` + `realm-workspace.json`): confidencial, code +
  PKCE S256, sem Direct Grant/implícito, redirect e post-logout exatos, `fullScopeAllowed=false` com escopo só em `admin`,
  `grafana-viewer` e `grafana-editor`; mapper `roles` (realm roles no claim `roles`). `grafana-*` nunca em
  `default-roles-workspace` e nunca atribuídas ao usuário final do Cadastro (provado pelo `GrafanaRealmIT` e smoke).
  `KC_GRAFANA_CLIENT_SECRET` só no `.env`.
- Fluxo `workspace-browser`: cookie ou LoA 1 (senha) → LoA 2 (OTP, só quando pedido) → OTP pela role
  (`Condition - user role`, que aceita uma só role, doc do Keycloak 26; sem OTP cadastrado, configura antes de concluir;
  um subfluxo por role: `log-investigator` e `admin`, RN-12) → OTP para quem já configurou. O LoA 1 usa o
  `legacy-username-password-form` (T15; sem a config `KC_SPI_AUTHENTICATOR__LEGACY_USERNAME_PASSWORD_FORM__*` ele não
  migra ninguém e age como o formulário padrão). Os dois últimos têm `Condition - credential` (OTP não usado nesta autenticação) para não
  pedir o código duas vezes. Fluxo `workspace-reset-credentials`: o reset conclui o login, então `log-investigator`
  digita o OTP depois do link; os demais seguem o reset padrão (`Reset OTP`). Cobertos por `LogInvestigatorOtpBypassIT`,
  `WorkspaceRealmIT` e smoke (requirement e config das condições).
- Fluxos no `provision.sh` por `ensure_flow`: fluxo ausente ou diferente da assinatura esperada (inclusive alterado à
  mão no Admin Console) é reconstruído em `<alias>-staging`, com subfluxos e configs também com `-staging` (não colidem
  com os do fluxo em uso); só depois de conferir a assinatura ele é ligado ao realm, a versão anterior é apagada e o
  sufixo é retirado. Staging largado é apagado, mas **antes** o realm é religado ao fluxo anterior (ou ao padrão `browser`
  / `reset credentials`) se ainda apontar para ele: o Keycloak recusa apagar fluxo em uso, e o realm nunca fica sem
  fluxo válido. Mudou a estrutura? Nova versão nos nomes de subfluxos/configs
  (`-v1-` → `-v2-` → `-v3-` no reset), assinatura atualizada e o mesmo no `realm-workspace.json`. O smoke altera o fluxo de login à mão,
  apaga o de reset e deixa o realm degradado (browserFlow no staging, sem `workspace-browser`) e confere que o provisioner repara tudo, deixando-os iguais aos versionados.
- Migração do Legado (ADR-0006): o cliente (`HttpLegacyUserClient`) só fala **HTTPS confiando só na CA do Legado**
  (`ca-file`, TLS 1.3, sem truststore do sistema), assina com HMAC-SHA256 (chave Base64 do Legado, `key-id`
  `keycloak-1`), timeout de 2 s e **sem retry**; não existe opção de config para liberar `http://`. A chave vem de
  `KC_LEGADO_SIGNING_KEY` (`.env`, copiada de `LEGADO_INTERNAL_KEYCLOAK_KEY` do `../legado-cadastro-usuarios/.env`) e nunca é versionada
  nem logada. A mesma URL/chave/CA é repetida por provider no `docker-compose.yml`
  (`KC_SPI_AUTHENTICATOR__LEGACY_USERNAME_PASSWORD_FORM__*`, `KC_SPI_AUTHENTICATOR__LEGACY_RESET_CHOOSE_USER__*`,
  `KC_SPI_EVENTS_LISTENER__LEGACY_MIGRATION_COMPLETION__*`); a CA pública é montada de `../legado-cadastro-usuarios/secrets/internal/ca.crt`
  (suba o Legado e rode o `internal-credentials.sh` dele antes: sem o arquivo o Docker cria um diretório no lugar).
  Dado do Legado recusado pelo User Profile nega o login (`LEGACY_UNAVAILABLE`, `error=invalid_profile`); o usuário é
  criado em transação própria já confirmada e o Legado é marcado logo em seguida (o listener cobre reset e atualização
  de senha). Riscos aceitos no ADR-0006: enumeração por tempo (até 2 s) e sem rate limit do Keycloak para contas só do Legado.
- Login social só com Google (ADR-0009): fluxos `workspace-first-broker-login` (conta existente + senha dela) e
  `workspace-post-broker-login` (OTP para `admin`, `log-investigator`, `user-importer` e quem já o configurou) no JSON e
  no `ensure_google_login`; o provedor vem de `provisioning/workspace-google-idp.json`, só pelo provisioner e só com
  `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` no `.env`. Role privilegiada nova entra nos dois lugares (browser e
  pós-broker). Provedor sem mappers, `storeToken=false`, `trustEmail=false`. Required action `idp_link` desligada (JSON
  declara a lista completa; `disable_session_account_linking`). Provado pelo `GoogleLoginIT` e `smoke-google.sh`.
- Realm com `eventsListeners` = `workspace-audit` + `legacy-migration-completion` (listeners recebem eventos mesmo com `eventsEnabled=false`, provado pelo `LegacyMigrationCompletionIT`), sem `jboss-logging`, com `eventsEnabled=false`, PKCE S256 por client policy, `admin-cli` sem password grant,
  brute force e política de senha ativos (cobertos por `WorkspaceRealmIT`).

## Glossário

| Termo | Nome no código | Definição |
|---|---|---|
| Registro de auditoria | `AuditRecord` | Evento convertido para a lista de permissão de campos |
| Listener de auditoria | `workspace-audit` | Event Listener SPI que escreve na categoria `workspace.audit` |
| Tema do Workspace | `workspace` | Tema de login em Bootstrap 5 que herda os templates do `base` (ADR-0003) |
| Investigador de logs | `log-investigator` | Realm role que o Cadastro exige para gerar consultas por token no Graylog (spec 001 do graylog) |
| Importador de usuários | `user-importer` / `cadastro-importer` | Realm role do admin que envia o CSV no Cadastro (OTP recente) / client de serviço que cria usuários sem senha na importação em lote (spec 002 do rabbitmq) |
| Fluxo de login | `workspace-browser` | Fluxo do navegador: login com migração do Legado (`legacy-username-password-form`), step-up por LoA (`acr` `password`/`otp`) e OTP obrigatório para `log-investigator` e `admin` |
| Fluxo de reset | `workspace-reset-credentials` | Reset de senha por e-mail com `legacy-reset-choose-user` (cria sem senha o usuário que só existe no Legado, T16); OTP obrigatório para `log-investigator` e `admin` |
| Listener de conclusão | `legacy-migration-completion` | Marca o usuário no Legado quando a senha é definida por reset ou atualização (T17); a migração no login marca por conta própria, logo após a transação interna confirmada (RN-07, independe de o login concluir); só `https://` com `ca-file` e TLS 1.3, sem opção para liberar http; config `KC_SPI_EVENTS_LISTENER__LEGACY_MIGRATION_COMPLETION__*` (inerte sem ela) |

## Estrutura

```
src/main/java/com/workspace/keycloak/audit/   Event Listener SPI (mapper puro, AuditSink: console ou Graylog, provider/factory)
src/main/java/com/workspace/keycloak/audit/gelf/  GELF HTTP para o Graylog, fila assíncrona e fallback em arquivo (ADR-0004)
src/main/java/com/workspace/keycloak/token/   Protocol Mapper do claim account_origin (ADR-0008)
src/main/java/com/workspace/keycloak/legacy/  Migração sob demanda do Legado: cliente HTTPS/HMAC, serviço, store, authenticators e listener (ADR-0006)
src/main/resources/theme/workspace/login/     Tema de login (CSS, logo, mensagens pt_BR/en)
src/main/resources/META-INF/                  Registro de providers (services) e temas
src/test/java/                                *Test (unitário) e *IT (Keycloak real via test-framework)
realm/realm-workspace.json                    Realm de dev importado no compose e nos testes de integração
theme-tests/                                  JS do tema: unit/ (Vitest + jsdom) e e2e/ (Playwright); lockfile versionado
provisioning/provision.sh                     Provisioner dos realms master e workspace (kcadm, idempotente)
scripts/                                      build, theme-unit, e2e, smoke-test, smoke-graylog, replay-fallback, sonar-analysis
.github/workflows/ci.yml                      CI (ADR-0005)
doc/                                          spec, plano, tarefas e ADRs
```

## Comandos complementares

```bash
# Chave do Legado (sem exibir o valor; o legado precisa ter rodado ./scripts/internal-credentials.sh):
grep -q '^KC_LEGADO_SIGNING_KEY=.' .env || { echo "KC_LEGADO_SIGNING_KEY=$(grep -m1 '^LEGADO_INTERNAL_KEYCLOAK_KEY=' ../legado-cadastro-usuarios/.env | cut -d= -f2- | tr -d '\r')" >> .env; }
./scripts/replay-fallback.sh           # reenvia ao Graylog a auditoria salva no fallback
docker compose -f docker-compose.yml -f docker-compose.graylog.yml up -d --build   # com Graylog (use sempre que ele estiver no ar)
./scripts/smoke-graylog.sh             # Graylog ok, master sem dado pessoal, fora (fallback), volta (alerta), reenvio
```

## Decisões

- ADR-0001: extensões em Java 21 + Maven, build/testes em container.
- ADR-0002: auditoria de eventos sem dados pessoais.
- ADR-0003: tema de login em Bootstrap 5 sobre o tema `base`.
- ADR-0004: logs no Graylog (auditoria por GELF HTTP com fallback local; servidor pelo Docker GELF driver).
- ADR-0005: CI e cadeia de suprimentos (ações por SHA, ShellCheck, Trivy com exceções que expiram).
- ADR-0006: migração sob demanda dos usuários do Legado por Authenticator + API do Legado (aceito).
- ADR-0007: identificador mascarado (`masked_login`) nos eventos de migração, exceção ao ADR-0002 (aceito).
- ADR-0008: claim `account_origin` no client `cadastro` por Protocol Mapper próprio (aceito).
- ADR-0009: login social só com Google, apenas para contas existentes (aceito).
