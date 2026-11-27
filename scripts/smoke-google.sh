#!/usr/bin/env bash
# Smoke do login social com Google (ADR-0009), contra o stack de pé e já provisionado. Chamado pelo smoke-test.sh e,
# sozinho, pelo CI antes de haver credenciais. Sem GOOGLE_CLIENT_ID/SECRET confere que o provedor não existe; com elas,
# confere o provedor e que o provisioner repara alterações, com e sem as variáveis. Nunca imprime credenciais.
# Uso (na raiz do projeto): bash ./scripts/smoke-google.sh
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="http://localhost:8180"
REALM="workspace"
failures=0
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

set -a
# shellcheck disable=SC1091
source .env
set +a

check() {
  local description="$1" ok="$2"
  if [[ "$ok" == "true" ]]; then echo "✅ ${description}"; else echo "❌ ${description}"; failures=$((failures + 1)); fi
}
contains() { grep -q -- "$2" <<<"$1" && echo true || echo false; }

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
run_provisioner() {
  local output
  output="$(docker compose run --rm --no-deps provisioner 2>&1)" || { tail -15 <<<"$output" >&2; return 1; }
}
login_page() {
  curl -fs -c "${work_dir}/cookies" -b "${work_dir}/cookies" -G "${BASE_URL}/realms/${REALM}/protocol/openid-connect/auth" \
    --data-urlencode "client_id=account-console" --data-urlencode "response_type=code" \
    --data-urlencode "scope=openid" --data-urlencode "redirect_uri=${BASE_URL}/realms/${REALM}/account/" \
    --data-urlencode "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" \
    --data-urlencode "code_challenge_method=S256"
}
providers() { admin_api GET "/identity-provider/instances"; }
provider_is_hardened() {
  local current
  current="$(providers)"
  [[ "$(contains "$current" '"storeToken":false')" == true && "$(contains "$current" '"trustEmail":false')" == true \
    && "$(contains "$current" '"firstBrokerLoginFlowAlias":"workspace-first-broker-login"')" == true \
    && "$(contains "$current" '"postBrokerLoginFlowAlias":"workspace-post-broker-login"')" == true \
    && "$(contains "$current" '"filteredByClaim":"true"')" == true \
    && "$(contains "$current" '"claimFilterName":"email_verified"')" == true \
    && "$(admin_api GET "/identity-provider/instances/google/mappers")" == "[]" ]] && echo true || echo false
}
set_provider_enabled() {
  admin_api GET "/identity-provider/instances/google" | sed "s/\"enabled\":[a-z]*/\"enabled\":$1/" \
    | admin_api PUT "/identity-provider/instances/google" --data-binary @- >/dev/null
}
# Estraga o provedor e o realm como um administrador distraído faria. O provedor fica desligado enquanto isso.
tamper_provider() {
  admin_api GET "/identity-provider/instances/google" \
    | sed -e 's/"enabled":[a-z]*/"enabled":false/' -e 's/"storeToken":false/"storeToken":true/' \
      -e 's/"trustEmail":false/"trustEmail":true/' -e 's/"postBrokerLoginFlowAlias":"[^"]*",\{0,1\}//' \
    | admin_api PUT "/identity-provider/instances/google" --data-binary @- >/dev/null
  printf '%s' '{"name":"smoke-tamper","identityProviderAlias":"google","identityProviderMapper":"hardcoded-attribute-idp-mapper","config":{"syncMode":"INHERIT","attribute":"smoke","attribute.value":"x"}}' \
    | admin_api POST "/identity-provider/instances/google/mappers" --data-binary @- >/dev/null
  admin_api GET "/authentication/required-actions/idp_link" | sed 's/"enabled":false/"enabled":true/' \
    | admin_api PUT "/authentication/required-actions/idp_link" --data-binary @- >/dev/null
}
account_linking_disabled() {
  contains "$(admin_api GET "/authentication/required-actions/idp_link")" '"enabled":false'
}

echo "== Login com Google (ADR-0009)"
# Resposta crua: todas as execuções importam, inclusive a última.
first_broker_flow="$(admin_api GET "/authentication/flows/workspace-first-broker-login/executions" || true)"
post_broker_flow="$(admin_api GET "/authentication/flows/workspace-post-broker-login/executions" || true)"
check "primeiro login externo exige conta existente e a senha dela (não cria usuário nem vincula sozinho)" \
  "$([[ "$(contains "$first_broker_flow" '"providerId":"idp-detect-existing-broker-user"')" == true \
    && "$(contains "$first_broker_flow" '"providerId":"idp-username-password-form"')" == true \
    && "$(contains "$first_broker_flow" 'idp-create-user-if-unique\|idp-auto-link')" == false ]] && echo true || echo false)"
check "pós-broker pede OTP para admin, log-investigator, user-importer e para quem já o configurou" \
  "$([[ "$(grep -o '"providerId":"auth-otp-form"' <<<"$post_broker_flow" | grep -c .)" == 4 \
    && "$(contains "$post_broker_flow" 'workspace-broker-v1-otp-by-user-importer')" == true \
    && "$(contains "$post_broker_flow" '"providerId":"allow-access-authenticator"')" == true ]] && echo true || echo false)"
check "vínculo de conta pela sessão aberta desligado (required action idp_link)" "$(account_linking_disabled)"
check "Keycloak não registra o e-mail de login externo sem conta (categoria do authenticator desligada)" \
  "$(contains "$(docker compose exec -T keycloak printenv KC_LOG_LEVEL || true)" 'IdpDetectExistingBrokerUserAuthenticator:off')"
check "nenhum provedor de identidade além do google" \
  "$(grep -o '"alias":"[^"]*"' <<<"$(providers)" | grep -qv '"alias":"google"' && echo false || echo true)"

if [[ -z "${GOOGLE_CLIENT_ID:-}" || -z "${GOOGLE_CLIENT_SECRET:-}" ]]; then
  check "sem GOOGLE_CLIENT_ID/SECRET o provedor google não existe e não há botão" \
    "$([[ "$(providers)" == "[]" && "$(contains "$(login_page)" 'social-google')" == false ]] && echo true || echo false)"
elif [[ "$(providers)" == *'"enabled":false'* ]]; then
  echo "ℹ️  Provedor google desligado pelo administrador: só a configuração é conferida"
  check "provedor google sem token guardado, sem confiar no e-mail, com os fluxos do Workspace e sem mappers" \
    "$(provider_is_hardened)"
else
  check "provedor google sem token guardado, sem confiar no e-mail, com os fluxos do Workspace e sem mappers" \
    "$(provider_is_hardened)"
  broker_link="$(grep -o 'id="social-google"[^>]*href="[^"]*"' <<<"$(login_page)" | sed -e 's/.*href="//' -e 's/"$//' -e 's/&amp;/\&/g' || true)"
  google_redirect=""
  if [[ -n "$broker_link" ]]; then
    google_redirect="$(curl -s -o /dev/null -w '%{redirect_url}' -c "${work_dir}/cookies" -b "${work_dir}/cookies" "${BASE_URL}${broker_link}")"
  fi
  # Comparações no próprio bash: o client id não vai para a saída nem para a lista de processos.
  check "botão do Google redireciona para o Google com o client configurado e a redirect_uri cadastrada" \
    "$([[ "$google_redirect" == "https://accounts.google.com/o/oauth2/v2/auth?"* \
      && "$google_redirect" == *"client_id=${GOOGLE_CLIENT_ID}"* \
      && "$google_redirect" == *"redirect_uri=http%3A%2F%2Flocalhost%3A8180%2Frealms%2Fworkspace%2Fbroker%2Fgoogle%2Fendpoint"* \
      && "$google_redirect" == *"scope=openid+email"* ]] && echo true || echo false)"

  tamper_provider
  check "provisioner roda sobre o provedor alterado" "$(run_provisioner && echo true || echo false)"
  check "provisioner repara o provedor, remove o mapper, religa o pós-broker e desliga o idp_link" \
    "$([[ "$(provider_is_hardened)" == true && "$(account_linking_disabled)" == true ]] && echo true || echo false)"
  check "provisioner preserva o provedor desligado" "$(contains "$(providers)" '"enabled":false')"

  tamper_provider
  check "provisioner roda sem GOOGLE_* sobre o provedor existente" \
    "$(GOOGLE_CLIENT_ID='' GOOGLE_CLIENT_SECRET='' run_provisioner && echo true || echo false)"
  check "sem GOOGLE_* o provedor existente também é reconciliado, mantendo o client id e o estado desligado" \
    "$([[ "$(provider_is_hardened)" == true && "$(account_linking_disabled)" == true \
      && "$(providers)" == *"\"clientId\":\"${GOOGLE_CLIENT_ID}\""* && "$(providers)" == *'"enabled":false'* ]] && echo true || echo false)"
  set_provider_enabled true
  check "provedor religado ao fim do smoke" "$(contains "$(providers)" '"enabled":true')"
fi

if [[ "$failures" -gt 0 ]]; then
  echo "Smoke do login com Google falhou: ${failures} verificação(ões)."
  exit 1
fi
