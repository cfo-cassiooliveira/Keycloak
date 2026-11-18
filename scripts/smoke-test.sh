#!/usr/bin/env bash
# Smoke test ponta a ponta do Keycloak do Workspace (Spec 001): saúde, realm, providers, tema e auditoria sem
# dados pessoais. Por padrão também reinicia o Keycloak para provar que o import não sobrescreve o realm.
# Uso (na raiz do projeto, com o stack de pé): ./scripts/smoke-test.sh [--skip-restart]
set -euo pipefail

cd "$(dirname "$0")/.."

# localhost (não 127.0.0.1): é o hostname do Keycloak; os cookies do login só voltam para o mesmo host.
BASE_URL="http://localhost:8180"
MANAGEMENT_URL="http://127.0.0.1:9100"
REALM="workspace"
# Usuário inexistente usado na tentativa de login: não pode aparecer em nenhum log de auditoria.
PROBE_USERNAME="smoke.probe.$(date +%s)"
failures=0
work_dir="$(mktemp -d)"
# Remove os usuários descartáveis do Grafana (smoke.grafana.*) mesmo que o smoke aborte no meio.
cleanup() {
  local uid
  rm -rf "$work_dir"
  for uid in $(admin_api GET "/users?search=smoke.grafana." 2>/dev/null | grep -o '"id":"[^"]*"' | cut -d'"' -f4 || true); do
    admin_api DELETE "/users/${uid}" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

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

# Espera a mensagem aparecer no log do Keycloak desde "$1". O import do realm roda em segundo plano depois que o
# servidor já responde UP, então uma leitura única do log logo após o wait_ready pode chegar antes da mensagem.
wait_for_log() {
  local since="$1" message="$2"
  for _ in $(seq 1 30); do
    if docker compose logs --since "$since" keycloak 2>/dev/null | grep -q -- "$message"; then return 0; fi
    sleep 2
  done
  return 1
}

# Faz o login pelo formulário (fluxo real do navegador) com usuário inexistente.
# Uso: failed_form_login [realm] [client] [redirect_uri]   (padrão: realm do Workspace pela Account Console)
failed_form_login() {
  local realm="${1:-$REALM}" client="${2:-account-console}" redirect="${3:-${BASE_URL}/realms/${REALM}/account/}"
  local jar="${work_dir}/cookies-${realm}" page action
  page="$(curl -fs -c "$jar" -b "$jar" -G "${BASE_URL}/realms/${realm}/protocol/openid-connect/auth" \
    --data-urlencode "client_id=${client}" --data-urlencode "response_type=code" \
    --data-urlencode "scope=openid" --data-urlencode "redirect_uri=${redirect}" \
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

# O smoke test roda sobre a stack como ela foi subida (inclusive com o docker-compose.graylog.yml): não pode recriá-la.
keycloak_container="$(docker compose ps -q keycloak)"

echo "== Saúde"
check "/health/ready responde UP" "$(wait_ready && echo true || echo false)"
check "/health/live responde UP" "$(contains "$(curl -fs "${MANAGEMENT_URL}/health/live")" '"UP"')"
check "/health responde UP" "$(contains "$(curl -fs "${MANAGEMENT_URL}/health")" '"UP"')"

echo "== Realm e providers"
check "realm ${REALM} publica a configuração OIDC" \
  "$(curl -fs -o /dev/null -w '%{http_code}' "${BASE_URL}/realms/${REALM}/.well-known/openid-configuration" | grep -q 200 && echo true || echo false)"

# Conta de serviço criada pelo provisioner (client credentials); o segredo vai pela entrada padrão, sem quebra de linha.
admin_token="$(printf '%s' "$KC_AUTOMATION_CLIENT_SECRET" \
  | curl -fs "${BASE_URL}/realms/master/protocol/openid-connect/token" \
    --data-urlencode "grant_type=client_credentials" --data-urlencode "client_id=workspace-automation" \
    --data-urlencode "client_secret@-" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true)"
check "conta de serviço workspace-automation obtém token (client credentials)" "$([[ -n "$admin_token" ]] && echo true || echo false)"
realm_json="$(curl -fs -H "Authorization: Bearer ${admin_token}" "${BASE_URL}/admin/realms/${REALM}")"
# O Keycloak devolve a lista de listeners em ordem qualquer: confere os dois e que não há outro (jboss-logging grava username e IP).
event_listeners="$(grep -o '"eventsListeners":\[[^]]*\]' <<<"$realm_json" || true)"
check "realm usa os listeners workspace-audit e legacy-migration-completion" \
  "$([[ "$(contains "$event_listeners" '"workspace-audit"')" == true && "$(contains "$event_listeners" '"legacy-migration-completion"')" == true \
    && "$(contains "$event_listeners" 'jboss-logging')" == false ]] && echo true || echo false)"
check "realm não grava eventos nem ações administrativas no banco" \
  "$([[ "$(contains "$realm_json" '"eventsEnabled":false')" == true && "$(contains "$realm_json" '"adminEventsEnabled":false')" == true ]] && echo true || echo false)"
check "realm usa o tema de login workspace" "$(contains "$realm_json" '"loginTheme":"workspace"')"
check "realm não usa e-mail como username (padrão de username ^[a-z0-9._-]+$, reconciliado pelo provisioner)" "$(contains "$realm_json" '"registrationEmailAsUsername":false')"
server_info="$(curl -fs -H "Authorization: Bearer ${admin_token}" "${BASE_URL}/admin/serverinfo")"
check "provider workspace-audit carregado no servidor" "$(contains "$server_info" '"workspace-audit"')"
check "providers legacy-reset-choose-user e legacy-migration-completion carregados" \
  "$([[ "$(contains "$server_info" '"legacy-reset-choose-user"')" == true && "$(contains "$server_info" '"legacy-migration-completion"')" == true ]] && echo true || echo false)"

echo "== Realm master (provisioner)"
master_json="$(curl -fs -H "Authorization: Bearer ${admin_token}" "${BASE_URL}/admin/realms/master")"
check "master audita pelo workspace-audit (sem o jboss-logging, que grava username e IP)" \
  "$(contains "$master_json" '"eventsListeners":\["workspace-audit"\]')"
check "master com proteção contra força bruta" "$(contains "$master_json" '"bruteForceProtected":true')"
ropc_response="$(curl -s "${BASE_URL}/realms/master/protocol/openid-connect/token" \
  --data-urlencode "grant_type=password" --data-urlencode "client_id=admin-cli" \
  --data-urlencode "username=smoke.ropc" --data-urlencode "password=x")"
check "admin-cli do master recusa password grant" "$(contains "$ropc_response" 'unauthorized_client')"

# Investigação em logs (spec 001 do graylog, K01), lida pela Admin API com token novo a cada chamada.
automation_token() {
  printf '%s' "$KC_AUTOMATION_CLIENT_SECRET" \
    | curl -fs "${BASE_URL}/realms/master/protocol/openid-connect/token" \
      --data-urlencode "grant_type=client_credentials" --data-urlencode "client_id=workspace-automation" \
      --data-urlencode "client_secret@-" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true
}
admin_api() {
  local method="$1" path="$2"
  shift 2
  curl -fs -X "$method" -H "Authorization: Bearer $(automation_token)" -H 'Content-Type: application/json' \
    "${BASE_URL}/admin/realms/${REALM}${path}" "$@"
}
# Uma execução por linha, com a configuração da condição no lugar do id (que muda quando o fluxo é recriado).
flow_structure() {
  local line config_id
  admin_api GET "/authentication/flows/$1/executions" | sed 's/},{/}\n{/g' | while IFS= read -r line; do
    config_id="$(grep -o '"authenticationConfig":"[^"]*"' <<<"$line" | cut -d'"' -f4 || true)"
    sed -E 's/"(id|flowId|authenticationConfig)":"[^"]*",?//g' <<<"$line"
    if [[ -n "$config_id" ]]; then
      echo "  config: $(admin_api GET "/authentication/config/${config_id}" | grep -o '"config":{[^}]*}')"
    fi
  done
}
investigation_state() {
  local cadastro_id grafana_id
  grafana_id="$(admin_api GET "/clients?clientId=grafana" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  cadastro_id="$(admin_api GET "/clients?clientId=cadastro" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  for path in /roles/log-investigator /roles/default-roles-workspace/composites/realm \
    "/clients/${cadastro_id}/scope-mappings/realm" /authentication/flows/workspace-browser/executions \
    /authentication/flows/workspace-reset-credentials/executions \
    /roles/grafana-viewer /roles/grafana-editor "/clients/${grafana_id}/scope-mappings/realm" \
    "/clients/${grafana_id}/protocol-mappers/models" "/clients/${grafana_id}/default-client-scopes"; do
    echo "${path}: $(admin_api GET "$path" || echo MISSING)"
  done
  admin_api GET "" | grep -o '"browserFlow":"[^"]*"\|"resetCredentialsFlow":"[^"]*"\|"acr.loa.map":"[^}]*}"' \
    || echo "bindings: MISSING"
}
# Estrutura sem ids: usada para comparar um fluxo recriado do zero com o anterior.
flows_without_ids() {
  flow_structure workspace-browser
  flow_structure workspace-reset-credentials
  admin_api GET "" | grep -o '"browserFlow":"[^"]*"\|"resetCredentialsFlow":"[^"]*"' || true
}
# Roda o provisioner mostrando a cauda da saída quando falha (diagnóstico no próprio smoke).
run_provisioner() {
  local output
  output="$(docker compose run --rm --no-deps provisioner 2>&1)" || { tail -15 <<<"$output" >&2; return 1; }
}
state_before="$(investigation_state)"
check "provisioner é idempotente (reexecução sem erro)" \
  "$(run_provisioner && echo true || echo false)"
state_after="$(investigation_state)"
check "reexecução do provisioner não altera role, fluxos de login e reset nem escopo do cadastro" \
  "$([[ "$state_before" == "$state_after" ]] && echo true || echo false)"

echo "== Investigação em logs (spec 001 do graylog)"
check "role log-investigator existe" "$(contains "$state_after" '"name":"log-investigator"')"
check "log-investigator não é role padrão" \
  "$(grep '^/roles/default-roles-workspace' <<<"$state_after" | grep -q 'log-investigator' && echo false || echo true)"
check "client cadastro tem log-investigator no escopo do client" \
  "$(grep '^/clients/.*/scope-mappings/realm' <<<"$state_after" | grep -q '"name":"log-investigator"' && echo true || echo false)"
check "realm usa os fluxos workspace-browser e workspace-reset-credentials" \
  "$([[ "$(contains "$state_after" '"browserFlow":"workspace-browser"')" == true && "$(contains "$state_after" '"resetCredentialsFlow":"workspace-reset-credentials"')" == true ]] && echo true || echo false)"
check "acr password = LoA 1 e otp = LoA 2 (step-up)" \
  "$(contains "$state_after" '"acr.loa.map":"{\\"password\\":1,\\"otp\\":2}"')"
browser_flow="$(flow_structure workspace-browser)"
reset_flow="$(flow_structure workspace-reset-credentials)"
# Condição pela role log-investigator (sem negação) obrigatória, seguida de OTP obrigatório, no login e no reset.
role_then_otp() {
  grep -A2 '"providerId":"conditional-user-role"' <<<"$1" | tr -d '\n' \
    | grep -q '"requirement":"REQUIRED".*"config":{"condUserRole":"log-investigator","negate":"false"}.*"providerId":"\(conditional-credential\|auth-otp-form\)"' \
    && echo true || echo false
}
check "login: OTP obrigatório para log-investigator (condição REQUIRED, role sem negação, OTP em seguida)" \
  "$(role_then_otp "$browser_flow")"
check "login: step-up LoA 2 com OTP obrigatório e max-age 0" \
  "$(grep -A2 '"loa-condition-level":"2"' <<<"$browser_flow" | tr -d '\n' | grep -q '"loa-max-age":"0".*"requirement":"REQUIRED".*"providerId":"auth-otp-form"' && echo true || echo false)"
check "reset de senha: OTP obrigatório para log-investigator" "$(role_then_otp "$reset_flow")"

# Reconciliação do provisioner: fluxo de login alterado à mão (Admin Console), fluxo de reset apagado e um staging
# largado por execução interrompida. O provisioner recria os dois iguais e religa. Só ambiente local: durante a
# checagem o reset usa o fluxo padrão do Keycloak.
flows_reference="$(flows_without_ids)"
admin_api PUT "" -d '{"resetCredentialsFlow":"reset credentials"}' >/dev/null
reset_id="$(admin_api GET /authentication/flows | sed 's/},{/}\n{/g' | grep '"alias":"workspace-reset-credentials"' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
admin_api DELETE "/authentication/flows/${reset_id}" >/dev/null || true
otp_by_role_id="$(admin_api GET /authentication/flows/workspace-browser-v2-otp-by-role/executions | sed 's/},{/}\n{/g' \
  | grep '"providerId":"auth-otp-form"' | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
admin_api PUT /authentication/flows/workspace-browser-v2-otp-by-role/executions \
  -d "{\"id\":\"${otp_by_role_id}\",\"requirement\":\"DISABLED\",\"priority\":30}" >/dev/null || true
admin_api POST /authentication/flows \
  -d '{"alias":"workspace-browser-staging","providerId":"basic-flow","topLevel":true,"builtIn":false}' >/dev/null || true
check "login com OTP pela role desligado à mão (pré-condição da reconciliação)" \
  "$(contains "$(flow_structure workspace-browser)" '"requirement":"DISABLED"')"
check "provisioner reconcilia fluxo alterado à mão e recria o apagado" \
  "$(run_provisioner && echo true || echo false)"
check "fluxos reconciliados iguais aos versionados e religados ao realm" \
  "$([[ "$(flows_without_ids)" == "$flows_reference" ]] && echo true || echo false)"
check "nada sobra com sufixo -staging (fluxos e configs)" \
  "$([[ "$(contains "$(admin_api GET /authentication/flows)" '-staging')" == false ]] && echo true || echo false)"

# Regressão (QA X01): promoção interrompida deixa browserFlow ligado ao staging e sem workspace-browser. O delete do
# staging falhava ("currently in use") a cada execução; o provisioner agora religa antes de apagar e reconstrói.
admin_api POST /authentication/flows \
  -d '{"alias":"workspace-browser-staging","providerId":"basic-flow","topLevel":true,"builtIn":false}' >/dev/null || true
admin_api PUT "" -d '{"browserFlow":"workspace-browser-staging"}' >/dev/null
browser_id="$(admin_api GET /authentication/flows | sed 's/},{/}\n{/g' | grep '"alias":"workspace-browser"' \
  | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
admin_api DELETE "/authentication/flows/${browser_id}" >/dev/null || true
check "realm degradado (browserFlow no staging, sem workspace-browser) é a pré-condição" \
  "$([[ "$(contains "$(admin_api GET "")" '"browserFlow":"workspace-browser-staging"')" == true ]] && echo true || echo false)"
check "provisioner repara realm com browserFlow ligado ao staging" \
  "$(run_provisioner && echo true || echo false)"
check "realm reparado: fluxos iguais aos versionados, religados e sem -staging" \
  "$([[ "$(flows_without_ids)" == "$flows_reference" && "$(contains "$(admin_api GET /authentication/flows)" '-staging')" == false ]] && echo true || echo false)"

echo "== Cadastro (spec 001 do cadastro)"
cadastro_token() {
  printf '%s' "$KC_CADASTRO_CLIENT_SECRET" \
    | curl -fs "${BASE_URL}/realms/${REALM}/protocol/openid-connect/token" \
      --data-urlencode "grant_type=client_credentials" --data-urlencode "client_id=cadastro" \
      --data-urlencode "client_secret@-" --data-urlencode "scope=$1" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true
}
cadastro_admin_token="$(cadastro_token kc-admin-api)"
check "conta de serviço do cadastro lista usuários pela Admin API (scope kc-admin-api)" \
  "$(curl -fs -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${cadastro_admin_token}" \
    "${BASE_URL}/admin/realms/${REALM}/users?max=1" | grep -q 200 && echo true || echo false)"
check "token do cadastro sem o scope kc-admin-api é recusado pela Admin API" \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(cadastro_token openid)" \
    "${BASE_URL}/admin/realms/${REALM}/users?max=1" | grep -q 401 && echo true || echo false)"
check "realm permite reset de senha" "$(contains "$realm_json" '"resetPasswordAllowed":true')"
# Token novo: o do início expira (60 s) durante a reexecução do provisioner acima.
admin_token="$(printf '%s' "$KC_AUTOMATION_CLIENT_SECRET" \
  | curl -fs "${BASE_URL}/realms/master/protocol/openid-connect/token" \
    --data-urlencode "grant_type=client_credentials" --data-urlencode "client_id=workspace-automation" \
    --data-urlencode "client_secret@-" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true)"
user_profile="$(curl -fs -H "Authorization: Bearer ${admin_token}" "${BASE_URL}/admin/realms/${REALM}/users/profile" || true)"
check "User Profile tem os atributos do cadastro" \
  "$([[ "$(contains "$user_profile" '"name":"mobilePhone"')" == true && "$(contains "$user_profile" '"name":"legacyUserId"')" == true ]] && echo true || echo false)"

echo "== Grafana (spec 001 do grafana, T04)"
grafana_json="$(admin_api GET "/clients?clientId=grafana" || true)"
grafana_id="$(grep -o '"id":"[^"]*"' <<<"$grafana_json" | head -1 | cut -d'"' -f4 || true)"
check "client grafana existe, confidencial, só Authorization Code (sem Direct Grant, implícito ou conta de serviço)" \
  "$([[ "$(contains "$grafana_json" '"publicClient":false')" == true && "$(contains "$grafana_json" '"standardFlowEnabled":true')" == true \
    && "$(contains "$grafana_json" '"directAccessGrantsEnabled":false')" == true && "$(contains "$grafana_json" '"implicitFlowEnabled":false')" == true \
    && "$(contains "$grafana_json" '"serviceAccountsEnabled":false')" == true && "$(contains "$grafana_json" '"fullScopeAllowed":false')" == true ]] && echo true || echo false)"
check "client grafana: PKCE S256, redirect e post-logout exatos (sem curinga)" \
  "$([[ "$(contains "$grafana_json" '"pkce.code.challenge.method":"S256"')" == true \
    && "$(contains "$grafana_json" '"redirectUris":\["http://localhost:3000/login/generic_oauth"\]')" == true \
    && "$(contains "$grafana_json" '"post.logout.redirect.uris":"http://localhost:3000/login"')" == true ]] && echo true || echo false)"
check "client grafana tem o mapper roles (realm roles no claim roles)" \
  "$(contains "$(admin_api GET "/clients/${grafana_id}/protocol-mappers/models" || true)" '"claim.name":"roles"')"
grafana_scope="$(admin_api GET "/clients/${grafana_id}/scope-mappings/realm" || true)"
check "escopo do grafana: só admin, grafana-viewer e grafana-editor" \
  "$([[ "$(contains "$grafana_scope" '"name":"grafana-viewer"')" == true && "$(contains "$grafana_scope" '"name":"grafana-editor"')" == true \
    && "$(contains "$grafana_scope" '"name":"admin"')" == true && "$(contains "$grafana_scope" '"name":"user"')" == false ]] && echo true || echo false)"
default_roles="$(admin_api GET /roles/default-roles-workspace/composites/realm || true)"
# Falha explícita se a consulta não respondeu: resposta vazia não prova que as roles grafana-* ficaram fora do padrão.
check "roles grafana-* não são padrão (usuário final do Cadastro não as recebe)" \
  "$([[ -n "$default_roles" && "$default_roles" != *grafana-* ]] && echo true || echo false)"
grafana_ropc="$(printf '%s' "$KC_GRAFANA_CLIENT_SECRET" | curl -s "${BASE_URL}/realms/${REALM}/protocol/openid-connect/token" \
  --data-urlencode "grant_type=password" --data-urlencode "client_id=grafana" --data-urlencode "client_secret@-" \
  --data-urlencode "username=smoke.ropc" --data-urlencode "password=x")"
check "grafana recusa password grant (Direct Grant desligado)" "$(contains "$grafana_ropc" 'unauthorized_client')"
# Usuários descartáveis: com grafana-viewer o claim roles traz só essa role; o usuário final (só roles padrão) não traz nenhuma grafana-*.
grafana_probe_user() {
  local name="$1" role="${2:-}" uid
  admin_api POST /users -d "{\"username\":\"${name}\",\"enabled\":true}" >/dev/null || true
  uid="$(admin_api GET "/users?username=${name}&exact=true" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  if [[ -n "$role" ]]; then
    # Só id e name: a descrição da role tem acentos e o curl do Git Bash no Windows os reenvia fora de UTF-8.
    local role_id
    role_id="$(admin_api GET "/roles/${role}" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
    admin_api POST "/users/${uid}/role-mappings/realm" -d "[{\"id\":\"${role_id}\",\"name\":\"${role}\"}]" >/dev/null || true
  fi
  echo "$uid"
}
grafana_example_token() {
  admin_api GET "/clients/${grafana_id}/evaluate-scopes/generate-example-access-token?scope=openid&userId=$1" || true
}
viewer_id="$(grafana_probe_user smoke.grafana.viewer grafana-viewer)"
enduser_id="$(grafana_probe_user smoke.grafana.enduser)"
viewer_token="$(grafana_example_token "$viewer_id")"
enduser_token="$(grafana_example_token "$enduser_id")"
check "token do usuário grafana-viewer traz roles só com grafana-viewer" \
  "$([[ "$(contains "$viewer_token" '"roles":\["grafana-viewer"\]')" == true ]] && echo true || echo false)"
check "token do usuário final do Cadastro (sem role grafana-*) não traz roles grafana-*" \
  "$([[ -n "$enduser_token" && "$(contains "$enduser_token" 'grafana-')" == false ]] && echo true || echo false)"
admin_api DELETE "/users/${viewer_id}" >/dev/null || true
admin_api DELETE "/users/${enduser_id}" >/dev/null || true

echo "== Rede de identidade e e-mail"
# Requisição HTTP crua de dentro da rede workspace-identity, como faz o BFF do cadastro (imagem do próprio Keycloak:
# tem bash e /dev/tcp, sem precisar de outra imagem). A requisição chega pela entrada padrão.
kc_image="$(docker inspect -f '{{.Config.Image}}' "$keycloak_container")"
identity_http() {
  docker run --rm -i --network workspace-identity --entrypoint bash "$kc_image" \
    -c 'exec 3<>/dev/tcp/keycloak/8080 && cat >&3 && cat <&3'
}
check "rede workspace-identity existe" "$(docker network inspect workspace-identity >/dev/null 2>&1 && echo true || echo false)"
discovery="$(printf 'GET /realms/%s/.well-known/openid-configuration HTTP/1.0\r\nHost: keycloak:8080\r\n\r\n' "$REALM" | identity_http)"
check "backchannel: issuer continua o do navegador (localhost:8180)" \
  "$(contains "$discovery" "\"issuer\":\"${BASE_URL}/realms/${REALM}\"")"
check "backchannel: token endpoint responde no endereço interno (keycloak:8080)" \
  "$(contains "$discovery" "\"token_endpoint\":\"http://keycloak:8080/realms/${REALM}/protocol/openid-connect/token\"")"
token_body="grant_type=client_credentials&client_id=workspace-automation&client_secret=${KC_AUTOMATION_CLIENT_SECRET}"
token_response="$(printf 'POST /realms/master/protocol/openid-connect/token HTTP/1.0\r\nHost: keycloak:8080\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: %s\r\n\r\n%s' \
  "${#token_body}" "$token_body" | identity_http)"
internal_token="$(grep -o '"access_token":"[^"]*"' <<<"$token_response" | cut -d'"' -f4 || true)"
token_payload="$(cut -d. -f2 <<<"$internal_token" | tr '_-' '/+' | base64 -d 2>/dev/null || true)"
check "token emitido pela rede interna tem o issuer do navegador" \
  "$(contains "$token_payload" "\"iss\":\"${BASE_URL}/realms/master\"")"
check "Mailpit (SMTP de desenvolvimento) no ar em 127.0.0.1:8025" \
  "$(curl -fs -o /dev/null -w '%{http_code}' http://127.0.0.1:8025/api/v1/info | grep -q 200 && echo true || echo false)"

echo "== Tema"
login_page="$(curl -fs -H 'Accept-Language: pt-BR' -G "${BASE_URL}/realms/${REALM}/protocol/openid-connect/auth" \
  --data-urlencode "client_id=account-console" --data-urlencode "response_type=code" \
  --data-urlencode "scope=openid" --data-urlencode "redirect_uri=${BASE_URL}/realms/${REALM}/account/" \
  --data-urlencode "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" \
  --data-urlencode "code_challenge_method=S256")"
check "tela de login carrega o CSS do tema workspace" "$(contains "$login_page" '/login/workspace/css/workspace.css')"
check "tela de login em PT-BR" "$(contains "$login_page" 'Entrar no Workspace')"

echo "== Auditoria"
since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
failed_form_login
# Também no master (admins): o jboss-logging padrão gravaria o username no log do servidor (LGPD).
failed_form_login master security-admin-console "${BASE_URL}/admin/master/console/"
sleep 2
if docker compose exec -T keycloak printenv KC_SPI_EVENTS_LISTENER__WORKSPACE_AUDIT__GRAYLOG_URL >/dev/null 2>&1; then
  # Com o overlay do Graylog a auditoria vai para o Graylog (com fallback), não para o console.
  echo "ℹ️  Auditoria configurada para o Graylog: verificada por ./scripts/smoke-graylog.sh"
else
  audit_logs="$(docker compose logs --no-log-prefix --since "$since" keycloak 2>/dev/null | grep '"loggerName":"workspace.audit"' || true)"
  check "LOGIN_ERROR registrado no log de auditoria" "$(contains "$audit_logs" 'LOGIN_ERROR')"
  # O login chegou a validar a credencial (sem cookie, o erro seria cookie_not_found).
  check "LOGIN_ERROR com o motivo real (user_not_found)" "$(contains "$audit_logs" '\\"error\\":\\"user_not_found\\"')"
  check "LOGIN_ERROR registrado em nível WARN" \
    "$(grep 'LOGIN_ERROR' <<<"$audit_logs" | grep -q '"level":"WARN"' && echo true || echo false)"
  check "log de auditoria não contém o username digitado" \
    "$(grep -q -- "$PROBE_USERNAME" <<<"$audit_logs" && echo false || echo true)"
fi
check "nenhum log do servidor contém o username digitado (realm do Workspace e master)" \
  "$(docker compose logs --since "$since" keycloak 2>/dev/null | grep -q -- "$PROBE_USERNAME" && echo false || echo true)"

if [[ "${1:-}" != "--skip-restart" ]]; then
  echo "== Reinício"
  since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  docker compose restart keycloak >/dev/null
  check "Keycloak volta UP após reinício" "$(wait_ready && echo true || echo false)"
  check "reinício não reimporta o realm existente" \
    "$(wait_for_log "$since" "Realm '${REALM}' already exists" && echo true || echo false)"
fi

# Regressão: "docker compose run provisioner" sem --no-deps recriava o Keycloak só com o docker-compose.yml,
# desligando o envio da auditoria ao Graylog sem aviso (e o smoke-graylog.sh falhava depois).
check "smoke test não recria o Keycloak (mantém o override com que a stack subiu)" \
  "$([[ -n "$keycloak_container" && "$(docker compose ps -q keycloak)" == "$keycloak_container" ]] && echo true || echo false)"

echo
if [[ "$failures" -gt 0 ]]; then
  echo "Smoke test falhou: ${failures} verificação(ões)."
  exit 1
fi
echo "Smoke test OK."
