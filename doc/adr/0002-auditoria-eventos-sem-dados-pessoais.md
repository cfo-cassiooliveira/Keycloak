# ADR-0002: Auditoria de eventos do Keycloak sem dados pessoais

- **Status**: Proposto
- **Data**: 2026-11-12
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. IV.1, Art. IV.6, Art. VI.1, Art. VI.3

## Contexto

O OWASP A09 e o steering de observabilidade exigem registrar login, falhas de autenticação e ações administrativas.
O listener padrão do Keycloak (`jboss-logging`) e o armazenamento de eventos no banco (`eventsEnabled`) incluem
username, e-mail, IP, id de sessão e detalhes livres, o que conflita com a LGPD e com a regra "nenhum dado pessoal em log".

## Decisão

1. Listener próprio `workspace-audit` (Event Listener SPI) que gera **uma linha JSON por evento** com campos de uma
   **lista de permissão** (tipo, realm, client, id técnico do usuário, código de erro, operação/tipo de recurso admin,
   horário UTC), versionada por `audit_schema`.
2. Níveis: sucesso INFO, `*_ERROR` WARN, falha interna do listener ERROR (uma vez, sem dados do evento). O listener
   nunca propaga exceção para o fluxo de autenticação.
3. No realm `workspace`: remover `jboss-logging` dos listeners, não gravar eventos de usuário no banco (o listener
   continua recebendo os eventos, comprovado em teste de integração) e admin events sem representação.
4. IP não é registrado nesta versão (dúvida em aberto na spec).

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| `jboss-logging` nativo | Zero código | Username, IP e detalhes no log | Viola Art. IV.6 / LGPD |
| Mascarar no Graylog (pipeline) | Sem SPI | Dado pessoal já saiu do processo e trafega/armazena antes | Minimização deve ser na origem |
| Blocklist de campos | Menos código | Campo novo do Keycloak vaza por padrão | Allowlist falha fechado |
| Eventos no banco + consulta no Admin Console | Nativo | Guarda IP/username, sem retenção alinhada | LGPD; logs centralizados são o padrão |

## Consequências

- **Positivas**: auditoria pronta para alertas (A09) sem dados pessoais; contrato de log estável e testado.
- **Negativas / riscos**: sem IP, investigação de ataques distribuídos fica mais difícil; consultas de eventos no Admin Console
  ficam vazias para eventos de usuário.
- **Ações decorrentes**: T03–T05 da Spec 001; extração do JSON e alertas no Graylog (pendência P2).
