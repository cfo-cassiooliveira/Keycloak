# ADR-0001: Extensões do Keycloak em Java 21 + Maven, com build e testes em container

- **Status**: Proposto
- **Data**: 2026-11-12
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. II.1, Art. IV.7, Art. V.1, Governança (projetos fora da stack)

## Contexto

O Workspace precisa de um Keycloak próprio e customizável por SPIs. Os SPIs do Keycloak só podem ser implementados
na JVM (Java); a stack padrão do Workspace é .NET/Angular. A imagem oficial do Keycloak 26.8.2 roda em OpenJDK 21.
A máquina de desenvolvimento tem apenas o Oracle JDK 27 e não tem Maven. O `keycloak-test-framework` sobe o servidor
na JVM local durante os testes de integração, que precisa ser compatível com o Keycloak.

## Decisão

1. Extensões em **Java 21** (`maven.compiler.release=21`), **um único módulo Maven** gerando um JAR com todos os providers.
2. Dependências do Keycloak com escopo `provided`, versões pelo BOM `org.keycloak:keycloak-parent:26.8.2`; nenhuma
   biblioteca de terceiros embutida no JAR (o Keycloak não isola classloaders de providers).
3. Testes: JUnit 5 + AssertJ + Mockito (unitários) e `keycloak-test-framework` com a mesma versão do servidor (integração);
   cobertura com JaCoCo, mínimo 80% verificado no `mvn verify`.
4. **Maven Wrapper** versionado (checksum SHA-256 da distribuição fixado) e build/testes oficiais executados no container `maven:3.9-eclipse-temurin-21`
   (`scripts/build.sh`), também usado no stage de build do `Dockerfile`. Nada precisa ser instalado na máquina além do Docker.
5. Imagem final **otimizada** (`kc.sh build` no Dockerfile) a partir de `quay.io/keycloak/keycloak:26.8.2` fixada por digest,
   com PostgreSQL 17 exclusivo.

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| Instalar JDK 21 + Maven na máquina | Build nativo mais rápido, debug na IDE | Configuração manual por máquina; conflito com `JAVA_HOME` do JDK 27 | Mantida só como opção para IDE (Temurin 21 + toolchains), não como padrão |
| Usar o JDK 27 local | Já instalado | Keycloak 26.8 não roda em 27; testes de integração quebram | Incompatível |
| Gradle | Build mais rápido | Documentação e quickstarts do Keycloak usam Maven | Maven é o caminho documentado (Art. II) |
| JavaScript providers | Sem Java | Preview, só autenticadores/mappers/policies | Não cobre Event Listener/Theme e é preview |
| Multi-módulo Maven por SPI | Isolamento | Complexidade sem ganho com 2 providers | YAGNI; reavaliar quando houver ciclos de versão distintos |
| `start-dev` no compose | Simples | Diferente do modo de produção, rebuild a cada start | Imagem otimizada aproxima dev de produção |

## Consequências

- **Positivas**: qualquer máquina com Docker builda e testa igual ao CI; versão do Keycloak casada entre testes e runtime.
- **Negativas / riscos**: primeiro build mais lento (download do Keycloak para os testes — mitigado com volume de cache `~/.m2`);
  SPIs internos podem quebrar em upgrades (testes de integração detectam).
- **Ações decorrentes**: tarefas T01/T02 da Spec 001; registrar o projeto no SonarQube quando liberado (pendência P1).
