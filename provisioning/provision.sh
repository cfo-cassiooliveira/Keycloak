#!/usr/bin/env bash
# Provisionamento idempotente do realm master (R3 da revisão de 2026-11-12), pela CLI oficial (kcadm.sh):
#   - auditoria pelo listener workspace-audit (sem dados pessoais) no lugar do jboss-logging padrão, que grava
#     username e IP no log; nada de eventos ou ações administrativas gravados no banco (guardariam o IP);
#   - proteção contra força bruta para os administradores;
#   - client de serviço "workspace-automation" (client credentials) para scripts e testes;
#   - admin-cli do master sem password grant (fluxo proibido pelo security.md); admins usam o Admin Console;
#   - reconcilia no realm workspace as configurações de auditoria/LGPD (o --import-realm não altera realm existente).
# Roda a cada "docker compose up" (serviço provisioner). Segredos chegam por variáveis de ambiente, nunca por argumento.
set -euo pipefail

: "${KC_URL:?KC_URL is required}"
: "${KC_AUTOMATION_CLIENT_SECRET:?KC_AUTOMATION_CLIENT_SECRET is required (run scripts/generate-env.sh)}"
AUTOMATION_CLIENT_ID="workspace-automation"
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
    -s bruteForceProtected=true >/dev/null
  log "workspace realm: audit listener and no event storage reconciled"
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
