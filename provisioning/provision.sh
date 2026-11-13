#!/usr/bin/env bash
# Provisionamento idempotente do realm master (R3 da revisão de 2026-11-12), pela CLI oficial (kcadm.sh):
#   - auditoria pelo listener workspace-audit (sem dados pessoais) no lugar do jboss-logging padrão, que grava
#     username e IP no log; nada de eventos ou ações administrativas gravados no banco (guardariam o IP);
#   - proteção contra força bruta para os administradores;
#   - client de serviço "workspace-automation" (client credentials) para scripts e testes;
#   - admin-cli do master sem password grant (fluxo proibido pelo security.md); admins usam o Admin Console;
#   - reconcilia no realm workspace as configurações de auditoria/LGPD, reset de senha, SMTP, roles, User Profile,
#     textos traduzidos e o client "cadastro" (o --import-realm não altera realm existente).
# Roda a cada "docker compose up" (serviço provisioner). Segredos chegam por variáveis de ambiente, nunca por argumento.
set -euo pipefail

: "${KC_URL:?KC_URL is required}"
: "${KC_AUTOMATION_CLIENT_SECRET:?KC_AUTOMATION_CLIENT_SECRET is required (run scripts/generate-env.sh)}"
AUTOMATION_CLIENT_ID="workspace-automation"
PROVISIONING_DIR="$(cd "$(dirname "$0")" && pwd)"
KCADM=/opt/keycloak/bin/kcadm.sh
CONFIG=/tmp/kcadm.config

log() { echo "[provision] $*" >&2; }
kc() { "$KCADM" "$@" --config "$CONFIG"; }

# Confere acesso real à Admin API (um token válido pode não ter a audiência ou o papel necessários).
has_admin_access() {
  kc get realms/master --fields realm >/dev/null 2>&1
}

# Primeiro tenta o client de serviço; na primeira execução (ou se ele estiver incompleto) usa o admin temporário.
login() {
  if KC_CLI_CLIENT_SECRET="$KC_AUTOMATION_CLIENT_SECRET" kc config credentials --server "$KC_URL" --realm master \
    --client "$AUTOMATION_CLIENT_ID" >/dev/null 2>&1 && has_admin_access; then
    log "logged in as service account ${AUTOMATION_CLIENT_ID}"
    return 0
  fi
  : "${KC_BOOTSTRAP_ADMIN_USERNAME:?bootstrap admin is required on the first run}"
  : "${KC_BOOTSTRAP_ADMIN_PASSWORD:?bootstrap admin is required on the first run}"
  KC_CLI_PASSWORD="$KC_BOOTSTRAP_ADMIN_PASSWORD" kc config credentials --server "$KC_URL" --realm master \
    --user "$KC_BOOTSTRAP_ADMIN_USERNAME" >/dev/null
  log "logged in as bootstrap admin (first run)"
}

client_id() {
  kc get clients -r master -q "clientId=$1" --fields id --format csv --noquotes | head -1
}

ensure_automation_client() {
  local id
  id="$(client_id "$AUTOMATION_CLIENT_ID")"
  # JSON pela entrada padrão: o segredo não aparece na lista de processos.
  local body
  # fullScopeAllowed=false: o token carrega só o papel mapeado abaixo (mínimo privilégio).
  body="$(printf '{"clientId":"%s","name":"Workspace automation","description":"Service account for provisioning, smoke tests and CI (client credentials only).","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"fullScopeAllowed":false,"secret":"%s"}' \
    "$AUTOMATION_CLIENT_ID" "$KC_AUTOMATION_CLIENT_SECRET")"
  if [[ -z "$id" ]]; then
    kc create clients -r master -f - <<<"$body" >/dev/null
    id="$(client_id "$AUTOMATION_CLIENT_ID")"
    log "client created: ${AUTOMATION_CLIENT_ID}"
  else
    kc update "clients/${id}" -r master -f - <<<"$body" >/dev/null
  fi
  kc add-roles -r master --uusername "service-account-${AUTOMATION_CLIENT_ID}" --rolename admin >/dev/null
  # O papel admin entra no token pelo mapeamento de escopo (fullScopeAllowed=false).
  kc get roles/admin -r master | sed '1s/^/[/; $s/$/]/' \
    | kc create "clients/${id}/scope-mappings/realm" -r master -f - >/dev/null
  # Keycloak 26: a Admin API exige no token a audiência da própria API, dada pelo client scope kc-admin-api.
  local scope_id
  # A imagem do Keycloak não tem awk: grep + cut sobre o CSV "id,name".
  scope_id="$(kc get client-scopes -r master --fields id,name --format csv --noquotes | grep ',kc-admin-api$' | cut -d, -f1)"
  kc update "clients/${id}/default-client-scopes/${scope_id}" -r master >/dev/null
}

harden_master_realm() {
  kc update realms/master \
    -s 'eventsListeners=["workspace-audit"]' \
    -s eventsEnabled=false \
    -s adminEventsEnabled=false \
    -s adminEventsDetailsEnabled=false \
    -s bruteForceProtected=true \
    -s failureFactor=5 \
    -s permanentLockout=false >/dev/null
  log "master realm: workspace-audit listener, no event storage, brute force protection"
}

# O --import-realm ignora realms que já existem: mudanças de segurança no realm-workspace.json não chegariam a
# ambientes já criados. Estas configurações são reconciliadas em toda execução (fonte da verdade: este script + JSON).
reconcile_workspace_realm() {
  if ! kc get realms/workspace --fields realm >/dev/null 2>&1; then
    log "realm workspace not found yet; skipping reconciliation"
    return 0
  fi
  kc update realms/workspace \
    -s 'eventsListeners=["workspace-audit"]' \
    -s eventsEnabled=false \
    -s adminEventsEnabled=false \
    -s adminEventsDetailsEnabled=false \
    -s bruteForceProtected=true \
    -s resetPasswordAllowed=true \
    -s 'smtpServer={"host":"mailpit","port":"1025","from":"no-reply@workspace.local","fromDisplayName":"Workspace","auth":"false","ssl":"false","starttls":"false"}' >/dev/null
  log "workspace realm: audit listener, no event storage, password reset and SMTP reconciled"
  ensure_workspace_roles
  kc update users/profile -r workspace -f "${PROVISIONING_DIR}/workspace-user-profile.json" >/dev/null
  kc create localization/pt-BR -r workspace -f "${PROVISIONING_DIR}/workspace-localization-pt-BR.json" >/dev/null
  kc create localization/en -r workspace -f "${PROVISIONING_DIR}/workspace-localization-en.json" >/dev/null
  log "workspace realm: user profile and localization texts applied"
  ensure_cadastro_client
}

# Roles do Cadastro (spec 001): "user" entra como role padrão de todo usuário novo.
ensure_workspace_roles() {
  local role
  for role in admin user; do
    if ! kc get "roles/${role}" -r workspace >/dev/null 2>&1; then
      kc create roles -r workspace -s "name=${role}" >/dev/null
      log "workspace realm: role created: ${role}"
    fi
  done
  kc add-roles -r workspace --rname default-roles-workspace --rolename user >/dev/null
}

workspace_client_id() {
  kc get clients -r workspace -q "clientId=$1" --fields id --format csv --noquotes | head -1
}

# BFF do Cadastro: client confidencial (Authorization Code + PKCE S256) com conta de serviço para a Admin API.
# Mínimo privilégio: fullScopeAllowed=false e escopo só com as roles necessárias.
ensure_cadastro_client() {
  : "${KC_CADASTRO_CLIENT_SECRET:?KC_CADASTRO_CLIENT_SECRET is required (run scripts/generate-env.sh)}"
  local id body realm_management_id role
  id="$(workspace_client_id cadastro)"
  body="$(printf '{"clientId":"cadastro","name":"Cadastro","description":"BFF do Cadastro: login OIDC (Authorization Code + PKCE) e conta de serviço para a Admin API.","enabled":true,"publicClient":false,"clientAuthenticatorType":"client-secret","protocol":"openid-connect","standardFlowEnabled":true,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":true,"frontchannelLogout":false,"fullScopeAllowed":false,"redirectUris":["http://localhost:4300/bff/signin-oidc"],"webOrigins":[],"attributes":{"pkce.code.challenge.method":"S256","post.logout.redirect.uris":"http://localhost:4300/"},"secret":"%s"}' \
    "$KC_CADASTRO_CLIENT_SECRET")"
  if [[ -z "$id" ]]; then
    kc create clients -r workspace -f - <<<"$body" >/dev/null
    id="$(workspace_client_id cadastro)"
    log "workspace realm: client created: cadastro"
  else
    kc update "clients/${id}" -r workspace -f - <<<"$body" >/dev/null
  fi
  printf '[%s,%s]' "$(kc get roles/admin -r workspace)" "$(kc get roles/user -r workspace)" \
    | kc create "clients/${id}/scope-mappings/realm" -r workspace -f - >/dev/null
  realm_management_id="$(workspace_client_id realm-management)"
  for role in view-users query-users manage-users; do
    printf '[%s]' "$(kc get "clients/${realm_management_id}/roles/${role}" -r workspace)" \
      | kc create "clients/${id}/scope-mappings/clients/${realm_management_id}" -r workspace -f - >/dev/null
  done
  kc add-roles -r workspace --uusername service-account-cadastro --cclientid realm-management \
    --rolename view-users --rolename query-users --rolename manage-users >/dev/null
  # Audiência da Admin API só quando a conta de serviço pede scope=kc-admin-api: tokens de login não a carregam.
  local scopes scope_name
  scopes="$(kc get client-scopes -r workspace --fields id,name --format csv --noquotes)"
  kc update "clients/${id}/optional-client-scopes/$(grep ',kc-admin-api$' <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null
  # Minimização (LGPD): tokens só com id, login e roles. Fora os scopes padrão que trazem nome, e-mail etc.
  for scope_name in basic acr roles; do
    kc update "clients/${id}/default-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null
  done
  for scope_name in profile email web-origins address phone microprofile-jwt organization; do
    kc delete "clients/${id}/default-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null 2>&1 || true
    kc delete "clients/${id}/optional-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null 2>&1 || true
  done
  if ! kc get "clients/${id}/protocol-mappers/models" -r workspace --fields name --format csv --noquotes | grep -qx login; then
    kc create "clients/${id}/protocol-mappers/models" -r workspace -f - >/dev/null <<'JSON'
{"name":"login","protocol":"openid-connect","protocolMapper":"oidc-usermodel-property-mapper","config":{"user.attribute":"username","claim.name":"preferred_username","jsonType.label":"String","id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"true"}}
JSON
  fi
  log "workspace realm: client cadastro reconciled"
}

disable_admin_cli_password_grant() {
  local id
  id="$(client_id admin-cli)"
  kc update "clients/${id}" -r master -s directAccessGrantsEnabled=false >/dev/null
  log "master admin-cli: password grant disabled"
}

login
ensure_automation_client
# Confirma que o client de serviço administra de fato antes de fechar o password grant (senão a automação perderia
# o acesso e só restaria o Admin Console).
KC_CLI_CLIENT_SECRET="$KC_AUTOMATION_CLIENT_SECRET" kc config credentials --server "$KC_URL" --realm master \
  --client "$AUTOMATION_CLIENT_ID" >/dev/null
if ! has_admin_access; then
  echo "Service account ${AUTOMATION_CLIENT_ID} has no Admin API access; password grant left enabled" >&2
  exit 1
fi
harden_master_realm
reconcile_workspace_realm
disable_admin_cli_password_grant
log "done"
