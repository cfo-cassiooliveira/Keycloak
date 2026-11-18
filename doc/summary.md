# Resumo — Spec 001 Keycloak do Workspace

Status da spec: Em revisão. Projeto `keycloak` v0.3.2 (pom). Resumo derivado de spec/plan/tasks, tasks.md e do código.

## Objetivo
Keycloak 26.8.2 próprio (Art. IV.2), reproduzível com `docker compose`, endurecido, com SPIs: auditoria sem dados pessoais e tema de login PT-BR. Evoluiu para migração sob demanda do Legado, Graylog e client `grafana`.

## Decisões-chave
- Java 21 + Maven, build/testes em container; JAR em `/opt/keycloak/providers`, imagem otimizada (ADR-0001).
- RN-01: log de auditoria só com allowlist (sem username, e-mail, IP, sessão); RN-03: listener nunca interrompe o login (ADR-0002; exceção `masked_login` no ADR-0007).
- Realm `workspace`: PKCE S256, sem Implicit/Direct Grant, brute force e senha forte, `eventsEnabled=false`, sem `jboss-logging`, sem segredos/usuários (RN-04 a RN-07).
- Tema Bootstrap 5 sobre `base` (ADR-0003); GELF HTTP com fallback em arquivo (ADR-0004); CI com Trivy/ShellCheck/gitleaks (ADR-0005); migração do Legado (ADR-0006).

## Contratos
- Portas só em `127.0.0.1`: 8180 (HTTP) e 9100 (`/health/live`, `/health/ready`, `/metrics`).
- Log JSON `audit_schema=1` (categoria `workspace.audit`); quebra de formato exige `audit_schema=2`.
- Realm `workspace`: `defaultLocale=pt-BR` (pt-BR, en), `loginTheme=workspace`, `eventsListeners` = `workspace-audit` (+ `legacy-migration-completion`).

## Estado das tarefas (tasks.md)
- Feitas: T01-T08, P1-P3, R1-R9, G1-G6, I2, I3, S4-S7, N8, B1 e itens extras (41 marcados `[x]`).
- Abertas (2): T09 (falta revisão `code-review-qa` e aceite dos ADRs 0001-0003, ainda "Proposto") e DoD "Revisão sem itens bloqueantes".
- Código presente: `audit/` (+ `gelf/`), `legacy/`, tema `workspace`, `provisioning/provision.sh`, `theme-tests/`.

## Links
[spec](spec.md) · [plan](plan.md) · [tasks](tasks.md) · [INDEX](INDEX.md) · [reference](reference.md)
