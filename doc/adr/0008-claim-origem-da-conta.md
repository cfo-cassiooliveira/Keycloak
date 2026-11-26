# ADR-0008: Claim com a origem da conta por Protocol Mapper próprio

- **Status**: Aceito (2026-11-26)
- **Data**: 2026-11-26
- **Decisores**: responsável técnico
- **Artigos da Constituição relacionados**: Art. IV.1 (LGPD, minimização), Art. IV.3, Art. IX
- **Relacionados**: [ADR-0006](0006-migracao-sob-demanda-do-legado.md) (migração sob demanda), tarefa P3 da
  [spec 002 do rabbitmq](../../../rabbitmq/docs/specs/002-uso-pelos-projetos/tasks.md)

## Contexto

O Cadastro precisa saber no token se a conta veio da importação em lote ou da migração do Legado (um único claim). A
origem já está marcada em atributos que só administrador ou serviço editam: `importLineId` (importador do Cadastro) e
`legacyUserId` (SPI de migração).

## Decisão

1. Claim `account_origin` com `imported` ou `migrated`, só nos tokens do client `cadastro`; sem atributo, sem claim.
2. `importLineId` tem precedência: o importador também marca a conta como migrada no Legado, e isso não muda a origem.
3. Derivação por Protocol Mapper próprio (`workspace-account-origin-mapper`), sem gravar nada novo no usuário.

## Alternativas consideradas

| Alternativa | Motivo da rejeição |
|---|---|
| Mapper nativo de atributo | Copia o valor (id da linha ou do Legado) para o token e não traduz para a origem |
| Mapper nativo de valor fixo | Não tem condição por atributo; iria para todas as contas |
| Roles ou grupos por origem | Exige atribuir no importador e na migração e preencher as contas já existentes |
| Atributo novo `accountOrigin` + mapper nativo | Mesmo custo: muda o importador (outro repositório) e exige preencher as contas existentes |
| Script mapper | Depende da funcionalidade preview `scripts` |

## Consequências

- **Positivas**: vale para as contas já existentes sem migração de dados; nenhum identificador no token.
- **Negativas / riscos**: mais uma classe sobre SPI interna do Keycloak (revisar a cada upgrade); a confiança depende
  de os dois atributos continuarem editáveis só por `admin` (quem tem `manage-users` consegue alterá-los).
