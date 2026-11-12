# ADR-0005: CI e cadeia de suprimentos

- **Status**: Aceito
- **Data**: 2026-11-12
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. IV.7, Art. V.1, V.2, V.5

## Contexto

Até a versão 0.3.0, build, testes e cobertura dependiam de execução manual, e não havia varredura de vulnerabilidades
(revisão de código de 2026-11-12, itens R5 e 3). O SonarQube local não é alcançável pelos runners do GitHub, e scripts
shell e templates FreeMarker não têm analisador no SonarQube Community.

## Decisão

1. **GitHub Actions** (`.github/workflows/ci.yml`) em todo PR e push na `main`: build e testes Java (unitários e de
   integração com Keycloak real, JaCoCo ≥ 80%), testes do JavaScript do tema (Vitest), ShellCheck, Trivy nas dependências,
   imagem com Trivy, provisionamento, smoke test e E2E. O Quality Gate roda quando `SONAR_TOKEN` e `SONAR_HOST_URL`
   existem; sem eles, o job é pulado com aviso e vale a análise local antes do PR (`scripts/sonar-analysis.sh`).
2. **Ações fixadas por SHA de commit** (com a tag em comentário) e imagens por tag + digest: uma tag movida não altera o
   pipeline.
3. **SonarQube** analisa Java, POM e o tema (JS e CSS), com cobertura JaCoCo e lcov do Vitest. Scripts shell passam pelo
   ShellCheck; templates FTL são cobertos pelos testes de integração e E2E.
4. **Trivy** falha o CI em vulnerabilidade HIGH/CRITICAL com correção disponível. Exceção só no `.trivyignore.yaml`, com
   justificativa e **prazo de expiração**: ao vencer, o CI volta a falhar e a decisão é revista.
5. Exceção vigente: **CVE-2026-102554** (Guava 33.6.0 empacotado na imagem oficial do Keycloak 26.8.2, corrigido no
   Guava 33.7.2). Não há Keycloak com a correção em 2026-11-12, e trocar bibliotecas internas da imagem oficial cria uma
   combinação não suportada pelo projeto Keycloak. Prazo: 2026-12-12 ou o próximo upgrade do Keycloak, o que vier antes.

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| Ações por tag (`@v4`) | Simples, padrão do legado | Tag pode ser movida (supply chain) | SHA é a prática recomendada |
| OWASP Dependency-Check | Popular no Maven | Precisa de chave da NVD e é lento | Trivy cobre Maven, npm e imagem num só lugar |
| Substituir o Guava na imagem | Zera o CVE | Imagem fora do suporte do Keycloak | Risco maior que o CVE |
| Ignorar CVE sem prazo | Pipeline verde | Esquecimento permanente | Toda exceção expira |

## Consequências

- **Positivas**: nenhuma mudança entra sem build, testes, verificação estática e varredura; exceções têm dono e prazo.
- **Negativas / riscos**: CI mais lento (o teste de integração sobe o Keycloak); Quality Gate no CI depende de um
  SonarQube alcançável pelos runners.
- **Ações decorrentes**: rever a exceção do Guava até 2026-12-12; proteção da branch `main` exigindo o CI verde.
