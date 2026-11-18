# Keycloak do Workspace

> Steering de **produto e estrutura** deste repositório. Herda a Constituição (`C:\Workspace\CLAUDE.md`)
> e o steering técnico (`C:\Workspace\.claude\rules\`). Pode restringir, nunca relaxar, as regras herdadas.

## Produto

- **Propósito**: provedor de identidade (Keycloak 26.8.2) do Workspace, com extensões próprias (SPIs) e realm versionado.
- **Usuários**: usuário final (login), administrador do Keycloak, segurança/operação (auditoria), desenvolvedor (SPIs).
- **Bounded context**: Identity — dono do PostgreSQL `keycloak` (schema gerido pelo próprio Keycloak).
- **Fora de escopo**: clients de aplicações (nascem com cada aplicação), produção/cluster/HTTPS próprio.
- **Requisitos não funcionais**: guarda dados pessoais de usuários (LGPD); logs de auditoria sem dado pessoal (ADR-0002).

## Stack deste projeto (diferenças do padrão)

- **Java 21 + Maven** (não .NET): ADR-0001. Testes: JUnit 5 + AssertJ + Mockito + `keycloak-test-framework`; cobertura JaCoCo.
- Banco: PostgreSQL 17 dedicado. Realm: `workspace` (`realm/realm-workspace.json`, sem segredos, sem usuários).
- A versão do Keycloak está no `pom.xml` (`keycloak.version`) **e** no `Dockerfile`: mude as duas juntas.

## Comandos

```bash
./scripts/build.sh                     # mvn verify no container JDK 21 (unitários + integração + JaCoCo ≥ 80%)
./scripts/build.sh -B test -Dtest=X    # um teste específico
./scripts/generate-env.sh              # .env com segredos (uma vez)
./scripts/identity-network.sh          # rede workspace-identity (idempotente; o compose a exige como externa)
docker compose up -d --build           # sobe PostgreSQL + Keycloak + Mailpit sem Graylog (auditoria só no console)
./scripts/smoke-test.sh                # valida o ambiente de pé (--skip-restart para pular o reinício)
./scripts/theme-unit.sh                # testes do JavaScript do tema (Vitest, cobertura lcov)
./scripts/e2e.sh                       # E2E do tema com Playwright em container (ambiente de pé); repassa KC_AUTOMATION_CLIENT_SECRET do .env
docker compose run --rm --no-deps provisioner  # reaplica o provisionamento do master (idempotente; --no-deps não recria a stack)
./scripts/sonar-analysis.sh            # Quality Gate no SonarQube local (projeto keycloak; falha se reprovar)
# Chave do Legado, replay-fallback, Graylog (compose e smoke; com o Graylog no ar use SEMPRE o override): doc/reference.md#comandos-complementares
```

## Regras que não podem regredir

- Auditoria só com campos da allowlist (`AuditEventMapper`); campo novo exige revisão de LGPD e teste. Usuário só
  pelo id técnico (`user_id`, `target_user_id`, `actor_user_id`); nunca username, e-mail, IP ou sessão.
- OTP do `log-investigator` é configurado **antes** de atribuir a role (README): sem OTP, o primeiro login com a role
  cadastra o autenticador só com a senha.
- **Provisioner e smoke nunca rodam ao mesmo tempo** (nem `docker compose run provisioner` durante o `up`, nem dois
  smokes, nem smoke com provisioner): com execuções concorrentes houve falha intermitente `Parent flow doesn't exist`
  ou JSON inválido ao reconstruir fluxos. Causa não comprovada; espere o `provisioner` do `up` terminar antes de rodar
  outro.
- Nenhum registro de auditoria se perde: falha no Graylog vai para o fallback em arquivo e o container loga o erro;
  o envio ao Graylog é assíncrono e nunca atrasa o login (ADR-0004).
- O listener nunca propaga exceção para o fluxo de login.
- Imagens Docker por tag + digest; portas só em `127.0.0.1`; segredos só no `.env`.
- Listener padrão `jboss-logging` nunca ativo em log (níveis `trace`; master com `workspace-audit` pelo provisioner).
  Automação usa a conta de serviço `workspace-automation` (client credentials), nunca password grant.
- Mudança de segurança no `realm-workspace.json` também entra na reconciliação do `provisioning/provision.sh`
  (o `--import-realm` não altera realm existente).
- Scripts com bit de execução no git (`git update-index --chmod=+x`); CI roda ShellCheck e Trivy. Exceção de
  vulnerabilidade só no `.trivyignore.yaml`, com justificativa e prazo.
- Mudou recurso do tema (CSS/JS/imagem)? Incremente a versão do `pom.xml` (cache-busting `?v=`, ADR-0003).
- **Regras detalhadas** (exceção `masked_login`/ADR-0007, contrato com o Cadastro C03, client `grafana`, fluxos `workspace-browser`/reset, `ensure_flow`, migração do Legado, listeners do realm): [doc/reference.md](doc/reference.md#regras-detalhadas). Leia antes de mexer nessas áreas.

## Mais

- Índice de docs, specs e ADRs: [doc/INDEX.md](doc/INDEX.md). Glossário, estrutura e decisões: [doc/reference.md](doc/reference.md).

## Grafana

Client `grafana` e roles `grafana-*` consumidos pelo `../grafana` (ADR-0002 de lá); ordem de subida e testes em `../grafana/README.md`.
