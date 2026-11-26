# Índice — keycloak
- spec.md (Em revisão; [resumo](summary.md)) · plan.md · tasks.md (T09 aberta)
- adr/0001 Java+Maven em container (Aceito) · 0002 auditoria sem dado pessoal (Aceito) · 0003 tema Bootstrap (Aceito)
- adr/0004 Graylog+fallback · 0005 CI/supply chain · 0006 migração Legado · 0007 `masked_login` (todos Aceito)
- adr/0008 claim `account_origin` por Protocol Mapper (Aceito)
- [reference.md](reference.md): regras detalhadas, glossário, estrutura, decisões

Comandos: `./scripts/build.sh` · `docker compose up -d --build` · `./scripts/smoke-test.sh` · `./scripts/theme-unit.sh` · `./scripts/e2e.sh` · `./scripts/sonar-analysis.sh`

Onde: regras/fluxos → reference.md; operar → ../README.md; auditoria `src/main/java/.../audit`; Legado `.../legacy`; tema `src/main/resources/theme`; realm `realm/` e `provisioning/provision.sh`.
