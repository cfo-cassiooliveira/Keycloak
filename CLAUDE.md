# Keycloak do Workspace

> Steering de **produto e estrutura** deste repositório. Herda a Constituição (`C:\Workspace\CLAUDE.md`)
> e o steering técnico (`C:\Workspace\.claude\rules\`). Pode restringir, nunca relaxar, as regras herdadas.

## Produto

- **Propósito**: provedor de identidade (Keycloak 26.8.2) do Workspace, com extensões próprias (SPIs) e realm versionado.
- **Usuários**: usuário final (login), administrador do Keycloak, segurança/operação (auditoria), desenvolvedor (SPIs).
- **Bounded context**: Identity — dono do PostgreSQL `keycloak` (schema gerido pelo próprio Keycloak).
- **Fora de escopo**: clients de aplicações (nascem com cada aplicação), produção/cluster/HTTPS próprio.
- **Requisitos não funcionais**: guarda dados pessoais de usuários (LGPD); logs de auditoria sem dado pessoal (ADR-0002).

## Glossário

| Termo | Nome no código | Definição |
|---|---|---|
| Registro de auditoria | `AuditRecord` | Evento convertido para a lista de permissão de campos |
| Listener de auditoria | `workspace-audit` | Event Listener SPI que escreve na categoria `workspace.audit` |
| Tema do Workspace | `workspace` | Tema de login em Bootstrap 5 que herda os templates do `base` (ADR-0003) |

## Stack deste projeto (diferenças do padrão)

- **Java 21 + Maven** (não .NET): ADR-0001. Testes: JUnit 5 + AssertJ + Mockito + `keycloak-test-framework`; cobertura JaCoCo.
- Banco: PostgreSQL 17 dedicado. Realm: `workspace` (`realm/realm-workspace.json`, sem segredos, sem usuários).
- A versão do Keycloak está no `pom.xml` (`keycloak.version`) **e** no `Dockerfile`: mude as duas juntas.

## Estrutura

```
src/main/java/com/workspace/keycloak/audit/   Event Listener SPI (mapper puro + writer + provider/factory)
src/main/resources/theme/workspace/login/     Tema de login (CSS, logo, mensagens pt_BR/en)
src/main/resources/META-INF/                  Registro de providers (services) e temas
src/test/java/                                *Test (unitário) e *IT (Keycloak real via test-framework)
realm/realm-workspace.json                    Realm de dev importado no compose e nos testes de integração
e2e/                                          Testes E2E do workspace.js (Playwright 1.64.1, lockfile versionado)
scripts/                                      build.sh, generate-env.sh, smoke-test.sh, e2e.sh
doc/                                          spec, plano, tarefas e ADRs
```

## Comandos

```bash
./scripts/build.sh                     # mvn verify no container JDK 21 (unitários + integração + JaCoCo ≥ 80%)
./scripts/build.sh -B test -Dtest=X    # um teste específico
./scripts/generate-env.sh              # .env com segredos (uma vez)
docker compose up -d --build           # sobe PostgreSQL + Keycloak (127.0.0.1:8180, mgmt 127.0.0.1:9100)
./scripts/smoke-test.sh                # valida o ambiente de pé (--skip-restart para pular o reinício)
./scripts/e2e.sh                       # E2E do tema com Playwright em container (ambiente de pé)
```

## Regras que não podem regredir

- Auditoria só com campos da allowlist (`AuditEventMapper`); campo novo exige revisão de LGPD e teste.
- O listener nunca propaga exceção para o fluxo de login.
- Realm sem `jboss-logging`, com `eventsEnabled=false`, PKCE S256 por client policy, `admin-cli` sem password grant,
  brute force e política de senha ativos (cobertos por `WorkspaceRealmIT`).
- Imagens Docker por tag + digest; portas só em `127.0.0.1`; segredos só no `.env`.
- Mudou recurso do tema (CSS/JS/imagem)? Incremente a versão do `pom.xml` (cache-busting `?v=`, ADR-0003).

## Decisões

- ADR-0001: extensões em Java 21 + Maven, build/testes em container.
- ADR-0002: auditoria de eventos sem dados pessoais.
- ADR-0003: tema de login em Bootstrap 5 sobre o tema `base`.
