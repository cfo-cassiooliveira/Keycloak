# ADR-0004: Logs do Keycloak no Graylog, com fallback local da auditoria

- **Status**: Aceito
- **Data**: 2026-11-12
- **Decisores**: Cassio Oliveira (responsável técnico)
- **Artigos da Constituição relacionados**: Art. IV.6, Art. VI.1, VI.2, VI.3, Art. VII.1, VII.4

## Contexto

Os logs do Keycloak precisam ir para o Graylog com os dados mais ricos possíveis para rastrear falhas, sem dados
pessoais (LGPD), mas com o **código técnico do usuário** para saber quem foi. Se o Graylog não puder gravar, nada pode
se perder: o registro fica no Keycloak e o container informa que não foi possível salvar no Graylog e que o fallback
foi realizado.

O Keycloak 26 não tem handler GELF nativo, e GELF por TCP/UDP não confirma o recebimento: uma mensagem pode ser
"enviada" para um socket meio fechado e sumir sem erro, o que impede saber quando acionar o fallback.

## Decisão

1. **Auditoria** (login, falhas, ações administrativas) sai do listener `workspace-audit` por **GELF HTTP**
   (`graylog:12202/gelf`), que responde `202 Accepted` ao gravar. Só um 2xx conta como gravado.
2. Mensagem GELF 1.1 com os campos da allowlist (ADR-0002) mais: `event_id`, `realm_name`, `target_user_id` (UUID do
   usuário afetado em ações administrativas), `service_name`, `service_version`, `keycloak_version`, `environment`,
   `host`, `log_source=audit` e `trace_id`/`span_id` quando o tracing do Keycloak estiver ligado. Nada de username,
   e-mail, IP ou id de sessão.
3. Envio **assíncrono** (fila limitada, uma thread, timeout de 2 s por mensagem): um Graylog lento nunca atrasa o login.
4. **Fallback**: falha no envio, fila cheia ou desligamento com mensagens pendentes gravam a mensagem GELF original
   (uma por linha, pronta para reenvio) em `/opt/keycloak/data/log/audit-fallback.log`, num volume persistente. O
   container registra em ERROR `Could not save audit log to Graylog ...; fallback performed ...`: a primeira falha na
   hora e, durante a queda, no máximo um resumo por minuto com a contagem. Se até o arquivo falhar, a mensagem (sem
   dado pessoal) vai inteira para o log do container.
5. Ao voltar, o container informa a recuperação e envia o evento `AUDIT_FALLBACK_USED`, que aciona o alerta
   "Keycloak - Auditoria salva no fallback" no Graylog.
6. **Logs do servidor** (startup, erros) e do PostgreSQL vão pelo Docker GELF driver (tags `keycloak-server` e
   `keycloak-db`), como nos outros projetos; o pipeline do Graylog extrai nível, logger e texto do JSON. O Docker mantém
   cópia local.
7. Sem `graylog-url` configurada, a auditoria continua no console (desenvolvimento sem Graylog).
8. **Listener padrão `jboss-logging` neutralizado** (revisão de 2026-11-12): ele grava username e IP e, com os logs do
   servidor indo ao Graylog, esses dados seriam indexados. Em camadas: níveis `trace` no compose (nunca aparece no log,
   nem em realms criados depois); o `provisioner` troca o listener do realm `master` pelo `workspace-audit`; o pipeline
   do Graylog descarta mensagens do logger `org.keycloak.events`. Smoke tests verificam que o username não chega ao log.
9. **Reenvio e retenção do fallback**: `scripts/replay-fallback.sh` renomeia o arquivo de forma atômica (o Keycloak
   passa a gravar num arquivo novo), reenvia linha a linha exigindo 202, devolve só o que falhar e descarta o que passou
   da retenção do Graylog (`RETENTION_DAYS`, 30 dias): o fallback não guarda ids de usuário além do prazo (LGPD).
10. **Fila em memória** (risco aceito): o desligamento normal entrega ou salva a fila (`stop_grace_period: 30s` no compose,
    acima do timeout de desligamento de 4 s); um encerramento abrupto (SIGKILL, OOM) perde o que estiver na fila em
    memória, normalmente vazia e no máximo `graylog-queue-capacity` registros com o Graylog lento. Persistir cada evento
    em disco antes do envio dobraria a escrita do login sem ganho proporcional neste ambiente.
11. **Origem das mensagens no GELF HTTP** (risco aceito no ambiente local): os inputs GELF do Graylog não autenticam o
    remetente; qualquer container da rede `observability` ou processo local poderia forjar um evento de auditoria e
    acionar alertas. Em produção é obrigatório input com TLS e autenticação de cliente (mTLS) e rede dedicada entre
    Keycloak e Graylog.
12. **Tracing (R6)**: o registro já leva `trace_id`/`span_id` quando o tracing do Keycloak estiver ligado, mas ele não é
    ligado agora: o Graylog não armazena traces e ligar o exportador OTLP sem coletor só gera erros de envio. Liga-se junto
    com um backend de traces (ex.: OpenTelemetry Collector), em decisão própria.

## Alternativas consideradas

| Alternativa | Prós | Contras | Motivo da rejeição |
|---|---|---|---|
| GELF TCP do listener | Input já existente | Sem confirmação; perda silenciosa ao reconectar | Não permite fallback confiável |
| Só Docker GELF driver (stdout) | Zero código | UDP sem confirmação nem fallback; cópia local com rotação | Não atende "não perder nada" |
| Syslog nativo do Keycloak | Configuração | Sem confirmação; erro de envio não é tratado | Idem |
| Coletor externo (Fluent Bit/OTel Collector) com buffer em disco | Robusto, desacoplado | Mais um componente para operar | Desproporcional hoje; reavaliar em produção com volume alto |
| Envio síncrono no login | Simples | Graylog lento trava o login | Art. VII.1 / disponibilidade |
| Logar ERROR a cada registro no fallback | Literal | Inunda o log numa queda longa | Resumo por minuto + recuperação |

## Consequências

- **Positivas**: auditoria rica e sem dados pessoais no Graylog; nenhuma perda quando o Graylog cai; queda vira alerta.
- **Negativas / riscos**: reenvio do fallback é manual (`scripts/replay-fallback.sh`), não automático; uma mensagem pode
  ser gravada duas vezes se o Graylog gravar e a resposta se perder ou o reenvio for interrompido (o `event_id` permite
  deduplicar); riscos aceitos dos itens 10 e 11; IP continua fora (dúvida em aberto na Spec 001).
- **Ações decorrentes**: input GELF HTTP, índice, streams, pipeline e alertas no projeto `graylog`;
  `docker-compose.graylog.yml` e `scripts/smoke-graylog.sh` neste projeto.
