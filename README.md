# Keycloak do Workspace

Keycloak 26.8.2 do Workspace, reproduzível localmente com Docker, com o realm `workspace` versionado e as
extensões (SPIs) do Workspace:

| Extensão | SPI | O que faz |
|---|---|---|
| `workspace-audit` | Event Listener | Log JSON de auditoria (login, falhas, ações administrativas) **sem dados pessoais** ([ADR-0002](doc/adr/0002-auditoria-eventos-sem-dados-pessoais.md)) |
| `workspace` | Theme (login) | Telas de login em Bootstrap 5, responsivas, com a identidade do Workspace (logo CFO e paleta céu/pervinca/água) e PT-BR por padrão ([ADR-0003](doc/adr/0003-tema-login-bootstrap.md)) |

Os logs vão para o **Graylog** com fallback local da auditoria ([ADR-0004](doc/adr/0004-logs-graylog-com-fallback.md)) e o código é
analisado no **SonarQube** (projeto `keycloak`).

Planejamento: [spec](doc/spec.md) · [plano](doc/plan.md) · [tarefas](doc/tasks.md) · [ADRs](doc/adr/).

## Requisitos da máquina

Só o **Docker** (Docker Desktop com ≥ 4 GB livres). Não é preciso instalar JDK nem Maven: build e testes rodam
no container `maven:3.9.16-eclipse-temurin-21` ([ADR-0001](doc/adr/0001-extensoes-java-maven-build-em-container.md)).

O JDK 27 instalado na máquina **não** serve para os testes de integração (o Keycloak 26.8 roda em Java 21).
Para depurar na IDE, instale o JDK 21 sem alterar o `JAVA_HOME`:

```bash
winget install EclipseAdoptium.Temurin.21.JDK
# depois rode ./mvnw com JAVA_HOME apontando para o JDK 21 só nesse terminal
```

## Como usar

```bash
./scripts/build.sh                 # compila + testes unitários e de integração + cobertura (JaCoCo ≥ 80%)
./scripts/theme-unit.sh            # testes do JavaScript do tema (Vitest + jsdom, cobertura lcov)
./scripts/generate-env.sh          # cria/completa o .env com segredos aleatórios (idempotente)
./scripts/identity-network.sh      # cria a rede workspace-identity, compartilhada com cadastro e legado (idempotente)
docker compose up -d --build       # PostgreSQL 17 + Keycloak com as extensões + Mailpit + provisioner do realm master
./scripts/smoke-test.sh            # valida saúde, realms, provisioner, providers, tema e auditoria
./scripts/e2e.sh                   # testes E2E do tema (Playwright em navegador real, em container)
./scripts/sonar-analysis.sh        # build + testes + análise no SonarQube local (falha se o Quality Gate reprovar)
```

O CI (`.github/workflows/ci.yml`, [ADR-0005](doc/adr/0005-ci-e-cadeia-de-suprimentos.md)) roda tudo isso em cada PR,
mais ShellCheck e Trivy (dependências e imagem).

| Endereço | Uso |
|---|---|
| http://localhost:8180/admin | Admin Console (usuário `bootstrap-admin`, senha no `.env`) |
| http://localhost:8180/realms/workspace/account | Account Console (tela de login do Workspace) |
| http://127.0.0.1:9100/health/ready | Readiness (também `/health/live`, `/health` e `/metrics`) |
| http://127.0.0.1:8025 | Mailpit: caixa dos e-mails enviados pelo Keycloak em desenvolvimento (reset de senha) |

### Realm `workspace` e o Cadastro

O realm entrega ao [`cadastro`](../cadastro) o client confidencial `cadastro` (Authorization Code + PKCE S256, redirect
exato `http://localhost:4300/bff/signin-oidc`, sem password grant). A conta de serviço dele tem só `view-users`,
`query-users` e `manage-users` e precisa pedir `scope=kc-admin-api` para falar com a Admin API. Também entrega as roles
`admin` e `user` (`user` é padrão), o reset de senha (e-mails no Mailpit) e o User Profile com os atributos do cadastro
(`provisioning/workspace-user-profile.json`, editáveis só por admin). O segredo `KC_CADASTRO_CLIENT_SECRET` é gerado
pelo `generate-env.sh` e aplicado pelo provisioner; o mesmo valor vai para o `.env` do cadastro.

Containers da rede `workspace-identity` (ex.: BFF do cadastro) acessam o Keycloak em `http://keycloak:8080`. Com
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, token e JWKS respondem nesse endereço e o `issuer` continua
`http://localhost:8180/realms/...`, o mesmo que o navegador vê.

As portas são publicadas só em `127.0.0.1`. O PostgreSQL não tem porta publicada.

### Realm master e administradores

O serviço `provisioner` (`provisioning/provision.sh`, idempotente, roda a cada `up`) deixa o `master` assim:
auditoria pelo `workspace-audit` (o listener padrão grava username e IP), sem eventos gravados no banco, proteção contra
força bruta, `admin-cli` **sem password grant** e o client de serviço `workspace-automation` (client credentials,
segredo `KC_AUTOMATION_CLIENT_SECRET` do `.env`) para scripts e CI. Ele também reconcilia as configurações de auditoria
do realm `workspace` (o `--import-realm` não altera realm já existente). Para reaplicar: `docker compose run --rm --no-deps provisioner` (o `--no-deps` evita recriar a stack sem o override do Graylog).

O `bootstrap-admin` é **temporário** e só entra pelo Admin Console. Na primeira subida: Admin Console → realm `master` →
crie um administrador nominal com a role `admin`, configure OTP para ele (Required action *Configure OTP*) e apague o
`bootstrap-admin`.

## Graylog

```bash
# Graylog (../graylog) de pé antes; depois:
docker compose -f docker-compose.yml -f docker-compose.graylog.yml up -d --build
./scripts/smoke-graylog.sh         # Graylog ok, master sem dado pessoal, Graylog fora (fallback), volta (alerta), reenvio
./scripts/replay-fallback.sh       # reenvia ao Graylog o que ficou no fallback
```

- **Auditoria**: GELF HTTP para `graylog:12202/gelf` com confirmação (202), stream `Keycloak - Auditoria`. Campos:
  tipo/operação, erro, `user_id` / `target_user_id` / `actor_user_id` (ids técnicos), client, realm, `event_id`,
  versões, ambiente, host e `trace_id` quando há tracing. Sem username, e-mail, IP ou sessão.
- **Graylog fora do ar**: a mensagem vai para `/opt/keycloak/data/log/audit-fallback.log` (volume
  `keycloak-audit-fallback`) e o container loga `Could not save audit log to Graylog ...; fallback performed ...`
  (primeira falha na hora, depois um resumo por minuto). Na volta, o evento `AUDIT_FALLBACK_USED` gera alerta.
- **Reenviar o fallback**: `./scripts/replay-fallback.sh` renomeia o arquivo de forma atômica (sem corrida com o
  Keycloak), reenvia linha a linha exigindo 202, devolve ao fallback só o que falhar e descarta o que passou de
  `RETENTION_DAYS` (30 dias, a retenção do Graylog). Interrompido, retoma o lote na próxima execução.
- **Servidor e PostgreSQL**: Docker GELF driver (tags `keycloak-server`, `keycloak-db`), stream `Keycloak - Servidor`.
  O listener padrão `jboss-logging` (username e IP) fica em nível `trace` e o Graylog descarta o logger
  `org.keycloak.events`: nenhum username chega ao Graylog.
- Opções do listener: `KC_SPI_EVENTS_LISTENER__WORKSPACE_AUDIT__GRAYLOG_URL`, `..._GRAYLOG_TIMEOUT_MS` (2000),
  `..._GRAYLOG_QUEUE_CAPACITY` (10000), `..._FALLBACK_FILE`, `..._ENVIRONMENT` (local).

### Logs de auditoria sem Graylog

```bash
docker compose logs keycloak | grep '"loggerName":"workspace.audit"'
```

Cada evento é uma linha JSON com `audit_schema`, `kind`, `type`/`operation`, `realm_id`, `client_id`, `user_id`
(id técnico), `error` e `occurred_at` (UTC). Sucesso sai em INFO, falha esperada em WARN e erro do listener em ERROR.

## Tema de login

- Herda os templates do tema `base` e mapeia as classes `kc*` para Bootstrap no `theme.properties`; `template.ftl` (layout)
  e `login.ftl` são próprios. As demais telas (troca de senha, OTP, erro...) usam o layout e as classes mapeadas.
- Bootstrap 5.3.8 vem do WebJar no `pom.xml` (extraído no build); a fonte Manrope (OFL) fica em `resources/fonts`.
- Testes em `theme-tests/`: `unit/` (Vitest + jsdom, 100% do `workspace.js`) e `e2e/` (Playwright).
- **Mudou CSS, JS ou imagem do tema? Incremente a versão do `pom.xml`.** O Keycloak serve recursos com cache de 30 dias;
  a versão entra como `?v=` nos links e força o navegador a buscar a versão nova.

## Como criar um novo SPI

1. Crie o pacote em `src/main/java/com/workspace/keycloak/<feature>/` com a `*ProviderFactory` e o `*Provider`
   (dependências do Keycloak são `provided`; não embuta bibliotecas no JAR).
2. Registre a factory em `src/main/resources/META-INF/services/<interface da factory>`.
3. Escreva testes unitários da regra e um `*IT` com `@KeycloakIntegrationTest(config = WorkspaceServerConfig.class)`.
4. `./scripts/build.sh` e depois `docker compose up -d --build && ./scripts/smoke-test.sh`.

SPIs do Keycloak são internos e podem mudar entre versões: upgrade do Keycloak é sempre um PR próprio, com a mesma
versão no `pom.xml` e no `Dockerfile`, e backup do volume `keycloak-db-data` antes (o schema migra sem volta).

## Pendências

- Migração dos usuários do `legado`.
- Rever a exceção do Guava (CVE-2026-102554) até 2026-12-12 ou no próximo upgrade do Keycloak (ADR-0005).
- Produção: input GELF com mTLS e rede dedicada (ADR-0004), tracing com backend de traces (R6).
