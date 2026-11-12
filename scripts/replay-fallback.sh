#!/usr/bin/env bash
# Reenvia ao Graylog a auditoria salva no fallback do Keycloak (ADR-0004), sem perder nem duplicar registros:
#   1) renomeia o arquivo de forma atômica (o Keycloak passa a gravar num arquivo novo: sem corrida com o reenvio);
#   2) reenvia linha a linha pelo input GELF HTTP e só conta como reenviado o que receber 202;
#   3) o que falhar volta para o fallback; o que passou da retenção (LGPD, padrão 30 dias) é descartado;
#   4) se o reenvio for interrompido, a próxima execução retoma o lote pendente.
# Cada linha tem _event_id: reenvios repetidos por interrupção podem ser deduplicados no Graylog.
# Uso (na raiz do projeto, com o Keycloak de pé): ./scripts/replay-fallback.sh
set -euo pipefail

cd "$(dirname "$0")/.."

GELF_URL="${GELF_URL:-http://127.0.0.1:12202/gelf}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
FALLBACK_FILE="/opt/keycloak/data/log/audit-fallback.log"
BATCH_FILE="/opt/keycloak/data/log/audit-fallback.replaying.log"

# No Git Bash (Windows), MSYS_NO_PATHCONV evita que /opt/... vire C:/Program Files/Git/opt/...
in_keycloak() { MSYS_NO_PATHCONV=1 docker compose exec -T keycloak bash -c "$1"; }

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

state="$(in_keycloak "if [ -s '${BATCH_FILE}' ]; then echo resume; elif [ -s '${FALLBACK_FILE}' ]; then mv '${FALLBACK_FILE}' '${BATCH_FILE}' && echo new; else echo empty; fi")"
if [[ "$state" == "empty" ]]; then
  echo "Nada a reenviar: o fallback está vazio."
  exit 0
fi
[[ "$state" == "resume" ]] && echo "Retomando um reenvio interrompido."

in_keycloak "cat '${BATCH_FILE}'" > "${work_dir}/batch"
now="$(date +%s)"
sent=0 expired=0 failed=0
: > "${work_dir}/failed"

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" ]] && continue
  timestamp="$(grep -o '"timestamp":[0-9]*' <<<"$line" | cut -d: -f2 || true)"
  if [[ -n "$timestamp" ]] && (( now - timestamp > RETENTION_DAYS * 86400 )); then
    expired=$((expired + 1))
    continue
  fi
  status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H 'Content-Type: application/json' \
    --data-binary @- "$GELF_URL" <<<"$line" || true)"
  if [[ "$status" == "202" ]]; then
    sent=$((sent + 1))
  else
    failed=$((failed + 1))
    printf '%s\n' "$line" >> "${work_dir}/failed"
  fi
done < "${work_dir}/batch"

# Devolve ao fallback (acrescentando, pois o Keycloak pode ter gravado algo novo nesse meio tempo) e encerra o lote.
if [[ -s "${work_dir}/failed" ]]; then
  in_keycloak "cat >> '${FALLBACK_FILE}'" < "${work_dir}/failed"
fi
in_keycloak "rm -f '${BATCH_FILE}'"

echo "Reenviados: ${sent} · descartados por retenção (> ${RETENTION_DAYS} dias): ${expired} · falharam (de volta ao fallback): ${failed}"
[[ "$failed" -eq 0 ]]
