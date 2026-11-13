# Tarefas — Spec 001 Keycloak customizado do Workspace

Legenda: `[P]` pode rodar em paralelo · cada tarefa = 1 PR pequeno · testes **sempre** inclusos (Art. V.1).
Comando padrão de verificação: `scripts/build.sh` (= `./mvnw -B verify` no container `maven:3.9-eclipse-temurin-21`).

## Fase 0 — Fundação

- [x] **T01** — Repositório e projeto Maven
  - Arquivos: `.gitignore` (`.env`, `target/`, `.serena/`), `.gitattributes` (`*.sh text eol=lf`, `mvnw text eol=lf`),
    `.editorconfig`, `README.md` (PT-BR, inclui requisitos da máquina do plano §8), `CLAUDE.md` (de `_templates/project-CLAUDE.md`,
    seção **Comandos**), `pom.xml` (Java 21, BOM `keycloak-parent` 26.8.2, deps `provided`, JUnit 5, AssertJ, Mockito,
    JaCoCo com regra ≥ 80%), Maven Wrapper com `distributionSha256Sum`, `scripts/build.sh`, `doc/adr/0001`, `doc/adr/0002`.
  - Testes inclusos: teste unitário trivial do pacote raiz rodando no `scripts/build.sh` (prova o pipeline de teste + JaCoCo).
  - Pronto quando: `git init` feito, `scripts/build.sh` passa num clone limpo sem Maven/JDK 21 instalados localmente.

- [x] **T02** — Imagem e ambiente local (walking skeleton sem providers ainda)
  - Depende de: T01
  - Arquivos: `Dockerfile` (stage `extensions`: maven+temurin-21 → `mvn package -DskipTests`, o portão de testes é o `scripts/build.sh`; stage final: `quay.io/keycloak/keycloak:26.8.2@sha256:…`,
    copia JAR para `/opt/keycloak/providers`, `KC_DB=postgres`, `KC_HEALTH_ENABLED=true`, `KC_METRICS_ENABLED=true`, `kc.sh build`),
    `docker-compose.yml` (postgres:17@digest sem porta publicada, keycloak `start --optimized --import-realm`, portas `127.0.0.1:8180`
    e `127.0.0.1:9100`, healthchecks, `depends_on: service_healthy`), `.env.example`, `scripts/generate-env.sh`,
    `realm/realm-workspace.json` (versão mínima: realm + locale pt-BR), `scripts/smoke-test.sh`.
  - Testes inclusos: `smoke-test.sh` — `/health/ready` UP, `/realms/workspace/.well-known/openid-configuration` 200,
    subida sem `.env` falha com mensagem clara, restart não reimporta o realm.
  - Pronto quando: `scripts/generate-env.sh && docker compose up -d --build && scripts/smoke-test.sh` verde do zero.

## Fase 1 — Auditoria (Event Listener SPI) — US-02

- [x] **T03** — Mapper e registro de auditoria (regra pura)
  - Depende de: T01
  - Arquivos: `src/main/java/com/workspace/keycloak/audit/AuditRecord.java`, `AuditEventMapper.java`, `AuditLevel.java`
  - Testes inclusos: `AuditEventMapperTest` — LOGIN → INFO com campos permitidos; `*_ERROR` → WARN com `error`;
    admin event sem `representation`/`resourcePath`; evento com `username`/`email`/`ipAddress`/`details` arbitrários
    → nenhum aparece (teste de allowlist que falha com campo novo); `occurred_at` em UTC ISO-8601.
  - Pronto quando: cobertura do pacote ≥ 90%.

- [x] **T04** — Provider, factory e writer
  - Depende de: T03
  - Arquivos: `WorkspaceAuditEventListenerProvider.java`, `WorkspaceAuditEventListenerProviderFactory.java` (id `workspace-audit`),
    `AuditLogWriter.java`, `META-INF/services/org.keycloak.events.EventListenerProviderFactory`.
  - Testes inclusos: unitários com Mockito — nível correto por tipo, JSON de uma linha com `audit_schema=1`, exceção no mapper
    → um log ERROR sem dados do evento e **sem propagar** (RN-03); integração (`@KeycloakIntegrationTest`,
    `dependencyCurrentProject()`) — provider `workspace-audit` presente na server info e invocado num login real
    (validado: o listener recebe eventos com `eventsEnabled=false` — risco R7 descartado).
  - Pronto quando: `scripts/build.sh` verde.

- [x] **T05** — Realm configurado para a auditoria
  - Depende de: T02, T04
  - Arquivos: `realm/realm-workspace.json` (`eventsListeners=["workspace-audit"]`, `eventsEnabled` conforme T04,
    `adminEventsEnabled=true`, `adminEventsDetailsEnabled=false`, `enabledEventTypes`).
  - Testes inclusos: `smoke-test.sh` faz um login inválido pelo formulário do `account-console` e verifica em `docker compose logs`
    uma linha `workspace.audit` com `LOGIN_ERROR`, nível WARN e **sem** o username usado.
  - Pronto quando: smoke verde.

## Fase 2 — Segurança do realm — RN-05/06

- [x] **T06** [P] — Endurecimento do realm `workspace`
  - Depende de: T02
  - Arquivos: `realm/realm-workspace.json` (brute force, password policy, client policy `pkce-enforcer` S256, tempos de token,
    refresh token rotation, Direct Access Grants/Implicit desabilitados), `README.md` (troca do admin temporário por admin
    permanente com MFA).
  - Testes inclusos: integração via test-framework importando o realm — política de senha rejeita senha fraca; 6ª tentativa
    inválida bloqueia temporariamente; requisição de autorização sem PKCE é recusada; smoke valida a configuração pela Admin API.
  - Pronto quando: testes verdes e checklist de `security.md` (clients) aplicável conferido.

## Fase 3 — Tema de login (Theme) — US-03

- [x] **T07** [P] — Tema `workspace` para login
  - Depende de: T01
  - Arquivos: `src/main/resources/META-INF/keycloak-themes.json`, `theme/workspace/login/theme.properties` (`parent=keycloak.v2`,
    `locales=pt-BR,en`), `resources/css/workspace.css`, `resources/img/logo.svg` (placeholder), `messages/messages_pt_BR.properties`,
    `messages/messages_en.properties`.
  - Testes inclusos: unitário que garante as mesmas chaves em `pt_BR` e `en`; integração — login do realm com o tema contém o
    marcador do tema (classe CSS/logo) e texto PT-BR; login do `master` continua com o tema padrão.
  - Pronto quando: `scripts/build.sh` verde.

- [x] **T08** — Aplicar o tema no realm e validar no navegador
  - Depende de: T05, T07
  - Arquivos: `realm/realm-workspace.json` (`loginTheme=workspace`, `defaultLocale=pt-BR`).
  - Testes inclusos: smoke valida HTML do login; verificação com Playwright (snapshot de acessibilidade da tela de login,
    console sem erros) conforme preferência do Workspace.
  - Pronto quando: tela revisada pelo responsável técnico.

## Fase 4 — Entrega

- [ ] **T09** — Documentação e revisão (documentação feita; falta a revisão `code-review-qa` e aceite dos ADRs)
  - Depende de: T01–T08
  - Arquivos: `README.md` (como subir, como criar um novo SPI: factory + `META-INF/services` + teste), `CLAUDE.md`, ADRs → *Aceito*.
  - Testes inclusos: n/a (documentação) — reexecutar `scripts/build.sh` e `scripts/smoke-test.sh` no fechamento.
  - Pronto quando: revisão pela skill `code-review-qa` sem itens bloqueantes.

## Pendências fora do escopo (dependem de alterar outros projetos)

- [x] **P1** — (feito na 0.3.0, projeto `keycloak`) Registrar `workspace-keycloak` no `sonarqube/provisioning/projects.json` + `sonar-project.properties` (JaCoCo XML)
      e rodar o Quality Gate (Art. V.2).
- [x] **P2** — (feito na 0.3.0, ADR-0004) `docker-compose.graylog.yml` (GELF driver, tag `keycloak`) + stream, extração do JSON de auditoria e alertas
      (rajada de `LOGIN_ERROR`, erro do listener) no `graylog/provisioning/provision.sh`.
- [ ] **P3** — Spec de migração dos usuários do `legado` (importação `pbkdf2-sha512`; validar se o tamanho de chave de 256 bits
      do Identity V3 é aceito pelo hash provider nativo, senão Password Hash SPI próprio).

## Definition of Done da feature

- [x] Todos os critérios de aceite da spec cobertos por testes
- [x] `scripts/build.sh` (com JaCoCo ≥ 80%) e `scripts/smoke-test.sh` verdes do zero
- [x] Quality Gate SonarQube — aprovado na 0.3.0 (`./scripts/sonar-analysis.sh`)
- [ ] Revisão via skill `code-review-qa` sem itens bloqueantes

## Revisão de código de 2026-11-12 (`code-review-qa`)

- [x] **R1** (bloqueante) — Testes do `workspace.js`: E2E em `e2e/tests/login-ux.spec.js` (estado de envio, envio único,
      Caps Lock, link de provedor de uso único, console sem erros), via `scripts/e2e.sh`. Mutação confirmada: sem o JS,
      os 5 testes de comportamento falham.

Pendentes para a próxima atualização (decisão do responsável técnico):

- [x] **R2** (importante) — Admin events gravados no banco com IP e sem expiração: desligar a gravação se o listener
      continuar recebendo os eventos, ou definir `adminEventsExpiration` e registrar a retenção no ADR-0002.
- [x] **R3** (importante) — Endurecer o realm `master`: listener `workspace-audit` no lugar do `jboss-logging` (vaza
      username e IP em `LOGIN_ERROR`), client de serviço para automação e smoke test sem password grant.
- [x] **R4** (importante) — Teste de força bruta passa com `failureFactor: 30`: espaçar tentativas além do
      `quickLoginCheckMilliSeconds` e testar o login rápido separadamente.
- [x] **R5** (importante) — Pipeline de CI (build, testes, E2E, scan de dependências e imagem).
- [x] **R6** (importante) — Decidido na 0.3.1 (ADR-0004, item 12): não ligar tracing sem backend de traces. Parcial na 0.3.0: auditoria no Graylog já leva `service_name`, versões, ambiente e
      `trace_id`/`span_id` quando há tracing. Falta ligar o tracing do Keycloak (OpenTelemetry) e o destino dos traces.
- [x] **R7** (sugestão) — Idiomas: nome acessível contendo o texto visível (WCAG 2.5.3) e chave `languages` traduzida.
- [x] **R8** (sugestão) — Teste para alertas `info`/`success`/`warning`, username já informado e "tentar outro jeito".
- [x] **R9** (nits) — `COPY realm` desnecessário no Dockerfile, senha do admin fora dos argumentos do `curl`,
      renomear `ThemeMessagesTest`, fixar versões dos plugins Maven.

## Versão 0.3.0 — Logs no Graylog e Quality Gate no SonarQube (2026-11-12)

- [x] **G1** — Auditoria por GELF HTTP com confirmação, envio assíncrono e fallback em arquivo com erro no container
      (ADR-0004). Testes: unitários (`gelf/*Test`), integração com Keycloak real e Graylog falso (`WorkspaceAuditGraylogIT`).
- [x] **G2** — Campos ricos sem dados pessoais: `event_id`, `realm_name`, `target_user_id`, versões, ambiente, host e
      `trace_id`/`span_id` (com tracing). Testes: `AuditEventMapperTest`, `GelfMessageFactoryTest`, provider.
- [x] **G3** — `docker-compose.graylog.yml` (auditoria HTTP, servidor e PostgreSQL pelo Docker GELF driver) e volume
      persistente do fallback. Teste: `scripts/smoke-graylog.sh` (Graylog ok, fora e de volta) contra Graylog real.
- [x] **G4** — Projeto `graylog`: input GELF HTTP 12202, índice `Keycloak`, pipeline do JSON do servidor, streams e
      alertas; casos novos no smoke test do Graylog.
- [x] **G5** — Projeto `sonarqube`: projeto `keycloak` com token; `scripts/sonar-analysis.sh` aqui. Quality Gate aprovado
      (cobertura 94,8%, 0 bugs/vulnerabilidades/code smells/hotspots).
- [x] **G6** — Correção nos smoke tests: login pelo formulário usava `127.0.0.1` e o Keycloak respondia
      `cookie_not_found`; agora usa `localhost` e o teste exige o motivo real do erro.

## Versão 0.3.1 — Correções da revisão de 2026-11-12 (segunda rodada)

- [x] **B1** (bloqueante) — Username do realm `master` chegava ao Graylog: `jboss-logging` em `trace`, master com
      `workspace-audit` pelo `provisioner`, descarte do logger `org.keycloak.events` no Graylog. Testes: `smoke-test.sh`,
      `smoke-graylog.sh` (username ausente no log e no Graylog) e smoke do Graylog (descarte).
- [x] **I2** — `scripts/replay-fallback.sh` (renomeação atômica, 202 por linha, retoma lote interrompido). Teste:
      `smoke-graylog.sh` (registro da queda chega ao Graylog, fallback vazio).
- [x] **I3** — SonarQube analisa o tema (JS/CSS) com cobertura do Vitest (`theme-tests/unit`, 100% do `workspace.js`);
      scripts pelo ShellCheck no CI.
- [x] **S4/S6** — Riscos da fila em memória e da origem do GELF HTTP aceitos e registrados (ADR-0004);
      `stop_grace_period: 30s`.
- [x] **S5** — Retenção do fallback: reenvio descarta o que passou de 30 dias. Teste: `smoke-graylog.sh`.
- [x] **S7** — `GraylogSettings` falha na subida com URL, timeout ou fila inválidos. Teste: `GraylogSettingsTest`.
- [x] **N8** — Segredos e senhas dos scripts por stdin/arquivo, não por argumento.
- [x] **Extra** — Realm `workspace` reconciliado pelo provisioner (o `--import-realm` não altera realm existente);
      scripts com bit de execução no git; exceção do Guava (CVE-2026-102554) com prazo no `.trivyignore.yaml` (ADR-0005).

## Versão 0.3.2 — Correções da reavaliação de 2026-11-13

- [x] **B1** (bloqueante) — Borda dos campos do tema com 1,22:1 (WCAG 1.4.11): token `--ws-field-border` (3,65:1) nos
      campos e no botão de mostrar senha; foco em pervinca escuro. Teste: E2E `form field contrast`.
- [x] **I1** — `smoke-test.sh` recriava o Keycloak sem o `docker-compose.graylog.yml` (`docker compose run` sem
      `--no-deps`), desligando o envio ao Graylog. Teste: o smoke confere que o container é o mesmo do início ao fim.
- [x] **I2** — E2E do tema nos 3 projetos (desktop, tablet e celular) e com axe-core (sem violação séria/crítica).
- [x] **I3** — CI com gitleaks em todo o histórico (`security.md`).
