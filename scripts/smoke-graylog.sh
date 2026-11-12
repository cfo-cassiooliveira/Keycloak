#!/usr/bin/env bash
# Smoke test da integração com o Graylog (ADR-0004), com o Keycloak e o Graylog reais de pé:
#   1) falha de login chega ao Graylog com o id técnico do usuário e sem dados pessoais;
#   2) Graylog inalcançável: o registro vai para o arquivo de fallback e o container informa o erro;
#   3) Graylog de volta: o evento AUDIT_FALLBACK_USED chega (e aciona o alerta "Keycloak: auditoria salva no fallback").
# A queda é simulada desconectando o Keycloak da rede "observability" (o Graylog continua atendendo os outros projetos).
# Uso (na raiz do projeto): docker compose -f docker-compose.yml -f docker-compose.graylog.yml up -d --build
#                           ./scripts/smoke-graylog.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# localhost (não 127.0.0.1): é o hostname do Keycloak; os cookies do login só voltam para o mesmo host.
BASE_URL="http://localhost:8180"
GRAYLOG_URL="http://127.0.0.1:9000"
REALM="workspace"
CONTAINER="keycloak-keycloak-1"
NETWORK="observability"
FALLBACK_FILE="/opt/keycloak/data/log/audit-fallback.log"
PROBE_USERNAME="smoke.graylog.$(date +%s)"
PROBE_PASSWORD="Smoke-$(openssl rand -hex 12)-Aa1!"
failures=0
work_dir="$(mktemp -d)"
user_location=""

check() {
  local description="$1" ok="$2"
  if [[ "$ok" == "true" ]]; then echo "✅ ${description}"; else echo "❌ ${description}"; failures=$((failures + 1)); fi
}

contains() { grep -q -- "$2" <<<"$1" && echo true || echo false; }

cleanup() {
  # Garante a reconexão ao Graylog e remove o usuário de teste mesmo se uma verificação falhar.
  docker network connect "$NETWORK" "$CONTAINER" >/dev/null 2>&1 || true
  if [[ -n "$user_location" ]]; then
    curl -s -o /dev/null -X DELETE -H "Authorization: Bearer ${admin_token}" "$user_location" || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

if [[ ! -f ../graylog/.env ]]; then
  echo "Graylog .env não encontrado em ../graylog. Suba o projeto graylog antes." >&2
  exit 2
fi
# Mesmo usuário que o provisionador do Graylog usa (admin, senha GRAYLOG_ROOT_PASSWORD).
graylog_password="$(grep -E '^GRAYLOG_ROOT_PASSWORD=' ../graylog/.env | cut -d= -f2- || true)"
if [[ -z "$graylog_password" ]]; then
  echo "GRAYLOG_ROOT_PASSWORD não encontrado em ../graylog/.env." >&2
  exit 2
fi
set -a
# shellcheck disable=SC1091
source .env
set +a

# Busca no Graylog (últimos 10 min); devolve a resposta JSON crua da API de busca.
graylog_search() {
  local query="$1"
  curl -s -u "admin:${graylog_password}" -H "X-Requested-By: smoke-test" -H "Content-Type: application/json" \
    -X POST "${GRAYLOG_URL}/api/search/messages" \
    --data "{\"query\": \"${query}\", \"timerange\": {\"type\": \"relative\", \"range\": 600}, \"size\": 5}"
}

await_graylog() {
  local query="$1" marker="$2"
  for _ in $(seq 1 30); do
    local result
    result="$(graylog_search "$query")"
    if grep -q -- "$marker" <<<"$result"; then
      echo "$result"
      return 0
    fi
    sleep 2
  done
  echo ""
}

# Login pelo formulário (fluxo real do navegador) com senha errada.
failed_form_login() {
  local jar="${work_dir}/cookies-$1" page action
  page="$(curl -fs -c "$jar" -b "$jar" -G "${BASE_URL}/realms/${REALM}/protocol/openid-connect/auth" \
    --data-urlencode "client_id=account-console" --data-urlencode "response_type=code" \
    --data-urlencode "scope=openid" --data-urlencode "redirect_uri=http://localhost:8180/realms/${REALM}/account/" \
    --data-urlencode "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" \
    --data-urlencode "code_challenge_method=S256")"
  action="$(grep -o 'action="[^"]*"' <<<"$page" | head -1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
  curl -s -o /dev/null -c "$jar" -b "$jar" -X POST "$action" \
    --data-urlencode "username=${PROBE_USERNAME}" --data-urlencode "password=wrong-password"
}

echo "== Preparação"
admin_token="$(curl -fs "${BASE_URL}/realms/master/protocol/openid-connect/token" \
  --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
  --data-urlencode "username=${KC_BOOTSTRAP_ADMIN_USERNAME}" --data-urlencode "password=${KC_BOOTSTRAP_ADMIN_PASSWORD}" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)"
user_location="$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer ${admin_token}" -H 'Content-Type: application/json' \
  "${BASE_URL}/admin/realms/${REALM}/users" \
  --data "{\"username\":\"${PROBE_USERNAME}\",\"email\":\"${PROBE_USERNAME}@example.com\",\"firstName\":\"Smoke\",\"lastName\":\"Graylog\",\"emailVerified\":true,\"enabled\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"${PROBE_PASSWORD}\",\"temporary\":false}]}" \
  | grep -i '^location:' | tr -d '\r' | awk '{print $2}')"
user_id="${user_location##*/}"
check "usuário de teste criado (id técnico ${user_id})" "$([[ -n "$user_id" ]] && echo true || echo false)"

echo "== 1) Graylog disponível"
failed_form_login online
online_result="$(await_graylog "service_name:keycloak AND type:LOGIN_ERROR AND user_id:${user_id}" "$user_id")"
check "LOGIN_ERROR chegou ao Graylog com o id técnico do usuário" "$(contains "$online_result" "$user_id")"
check "registro no Graylog sem username nem e-mail" \
  "$([[ -n "$online_result" ]] && ! grep -q -- "$PROBE_USERNAME" <<<"$online_result" && echo true || echo false)"
admin_result="$(await_graylog "service_name:keycloak AND operation:CREATE AND target_user_id:${user_id}" "$user_id")"
check "criação do usuário (ação administrativa) chegou com o id do usuário afetado" "$(contains "$admin_result" "$user_id")"

echo "== 2) Graylog inalcançável"
since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
docker network disconnect "$NETWORK" "$CONTAINER"
failed_form_login offline
fallback_content=""
for _ in $(seq 1 15); do
  fallback_content="$(MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" cat "$FALLBACK_FILE" 2>/dev/null || true)"
  grep -q "LOGIN_ERROR" <<<"$fallback_content" && grep -q -- "$user_id" <<<"$fallback_content" && break
  sleep 2
done
check "LOGIN_ERROR salvo no arquivo de fallback do Keycloak" \
  "$(grep -q "LOGIN_ERROR" <<<"$fallback_content" && grep -q -- "$user_id" <<<"$fallback_content" && echo true || echo false)"
check "fallback sem username nem e-mail" "$(grep -q -- "$PROBE_USERNAME" <<<"$fallback_content" && echo false || echo true)"
container_logs="$(docker logs --since "$since" "$CONTAINER" 2>&1)"
check "container informa: não foi possível salvar no Graylog, fallback realizado" \
  "$(grep -q "Could not save audit log to Graylog" <<<"$container_logs" && grep -q "fallback performed" <<<"$container_logs" && echo true || echo false)"

echo "== 3) Graylog de volta"
docker network connect "$NETWORK" "$CONTAINER"
failed_form_login recovered
recovery_result="$(await_graylog "service_name:keycloak AND type:AUDIT_FALLBACK_USED" "AUDIT_FALLBACK_USED")"
check "evento AUDIT_FALLBACK_USED chegou ao Graylog (alerta de fallback)" "$(contains "$recovery_result" "AUDIT_FALLBACK_USED")"
check "container informa que o Graylog voltou" \
  "$(docker logs --since "$since" "$CONTAINER" 2>&1 | grep -q "Graylog is reachable again" && echo true || echo false)"

echo
if [[ "$failures" -gt 0 ]]; then
  echo "Smoke test do Graylog falhou: ${failures} verificação(ões)."
  exit 1
fi
echo "Smoke test do Graylog OK."
