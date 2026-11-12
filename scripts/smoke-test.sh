#!/usr/bin/env bash
# Smoke test ponta a ponta do Keycloak do Workspace (Spec 001): saúde, realm, providers, tema e auditoria sem
# dados pessoais. Por padrão também reinicia o Keycloak para provar que o import não sobrescreve o realm.
# Uso (na raiz do projeto, com o stack de pé): ./scripts/smoke-test.sh [--skip-restart]
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="http://127.0.0.1:8180"
MANAGEMENT_URL="http://127.0.0.1:9100"
REALM="workspace"
# Usuário inexistente usado na tentativa de login: não pode aparecer em nenhum log de auditoria.
PROBE_USERNAME="smoke.probe.$(date +%s)"
failures=0
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

check() {
  local description="$1" ok="$2"
  if [[ "$ok" == "true" ]]; then echo "✅ ${description}"; else echo "❌ ${description}"; failures=$((failures + 1)); fi
}

contains() { grep -q -- "$2" <<<"$1" && echo true || echo false; }

wait_ready() {
  for _ in $(seq 1 60); do
    if curl -fs "${MANAGEMENT_URL}/health/ready" 2>/dev/null | grep -q '"status": *"UP"'; then return 0; fi
    sleep 5
  done
  return 1
}

# Faz o login pelo formulário (fluxo real do navegador) com usuário inexistente.
failed_form_login() {
  local jar="${work_dir}/cookies" page action
  page="$(curl -fs -c "$jar" -b "$jar" -G "${BASE_URL}/realms/${REALM}/protocol/openid-connect/auth" \
    --data-urlencode "client_id=account-console" --data-urlencode "response_type=code" \
    --data-urlencode "scope=openid" --data-urlencode "redirect_uri=${BASE_URL/127.0.0.1/localhost}/realms/${REALM}/account/" \
    --data-urlencode "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" \
    --data-urlencode "code_challenge_method=S256")"
  action="$(grep -o 'action="[^"]*"' <<<"$page" | head -1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
  curl -s -o /dev/null -c "$jar" -b "$jar" -X POST "$action" \
    --data-urlencode "username=${PROBE_USERNAME}" --data-urlencode "password=smoke-wrong-password"
}

echo "== Configuração"
set +e
missing_env_output="$(docker compose --env-file /dev/null config 2>&1 >/dev/null)"
set -e
check "subida sem .env falha pedindo o generate-env.sh" "$(contains "$missing_env_output" "generate-env.sh")"
check "nenhuma porta publicada fora de 127.0.0.1" \
  "$(docker compose config 2>/dev/null | grep -E 'host_ip:' | grep -v -q '127.0.0.1' && echo false || echo true)"

set -a
# shellcheck disable=SC1091
source .env
set +a

echo "== Saúde"
check "/health/ready responde UP" "$(wait_ready && echo true || echo false)"
check "/health/live responde UP" "$(contains "$(curl -fs "${MANAGEMENT_URL}/health/live")" '"UP"')"
check "/health responde UP" "$(contains "$(curl -fs "${MANAGEMENT_URL}/health")" '"UP"')"

echo "== Realm e providers"
check "realm ${REALM} publica a configuração OIDC" \
  "$(curl -fs -o /dev/null -w '%{http_code}' "${BASE_URL}/realms/${REALM}/.well-known/openid-configuration" | grep -q 200 && echo true || echo false)"

token_response="$(curl -fs "${BASE_URL}/realms/master/protocol/openid-connect/token" \
  --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
  --data-urlencode "username=${KC_BOOTSTRAP_ADMIN_USERNAME}" --data-urlencode "password=${KC_BOOTSTRAP_ADMIN_PASSWORD}")"
admin_token="$(grep -o '"access_token":"[^"]*"' <<<"$token_response" | cut -d'"' -f4)"
realm_json="$(curl -fs -H "Authorization: Bearer ${admin_token}" "${BASE_URL}/admin/realms/${REALM}")"
check "realm usa só o listener workspace-audit" "$(contains "$realm_json" '"eventsListeners":\["workspace-audit"\]')"
check "realm não grava eventos de usuário no banco" "$(contains "$realm_json" '"eventsEnabled":false')"
check "realm usa o tema de login workspace" "$(contains "$realm_json" '"loginTheme":"workspace"')"
server_info="$(curl -fs -H "Authorization: Bearer ${admin_token}" "${BASE_URL}/admin/serverinfo")"
check "provider workspace-audit carregado no servidor" "$(contains "$server_info" '"workspace-audit"')"

echo "== Tema"
login_page="$(curl -fs -H 'Accept-Language: pt-BR' -G "${BASE_URL}/realms/${REALM}/protocol/openid-connect/auth" \
  --data-urlencode "client_id=account-console" --data-urlencode "response_type=code" \
  --data-urlencode "scope=openid" --data-urlencode "redirect_uri=${BASE_URL/127.0.0.1/localhost}/realms/${REALM}/account/" \
  --data-urlencode "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" \
  --data-urlencode "code_challenge_method=S256")"
check "tela de login carrega o CSS do tema workspace" "$(contains "$login_page" '/login/workspace/css/workspace.css')"
check "tela de login em PT-BR" "$(contains "$login_page" 'Entrar no Workspace')"

echo "== Auditoria"
since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
failed_form_login
sleep 2
audit_logs="$(docker compose logs --no-log-prefix --since "$since" keycloak 2>/dev/null | grep '"loggerName":"workspace.audit"' || true)"
check "LOGIN_ERROR registrado no log de auditoria" "$(contains "$audit_logs" 'LOGIN_ERROR')"
check "LOGIN_ERROR registrado em nível WARN" \
  "$(grep 'LOGIN_ERROR' <<<"$audit_logs" | grep -q '"level":"WARN"' && echo true || echo false)"
check "log de auditoria não contém o username digitado" \
  "$(grep -q -- "$PROBE_USERNAME" <<<"$audit_logs" && echo false || echo true)"
check "nenhum log do servidor contém o username digitado" \
  "$(docker compose logs --since "$since" keycloak 2>/dev/null | grep -q -- "$PROBE_USERNAME" && echo false || echo true)"

if [[ "${1:-}" != "--skip-restart" ]]; then
  echo "== Reinício"
  since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  docker compose restart keycloak >/dev/null
  check "Keycloak volta UP após reinício" "$(wait_ready && echo true || echo false)"
  check "reinício não reimporta o realm existente" \
    "$(docker compose logs --since "$since" keycloak 2>/dev/null | grep -q "Realm '${REALM}' already exists" && echo true || echo false)"
fi

echo
if [[ "$failures" -gt 0 ]]; then
  echo "Smoke test falhou: ${failures} verificação(ões)."
  exit 1
fi
echo "Smoke test OK."
