#!/usr/bin/env bash
# Provisionamento idempotente do realm master (R3 da revisão de 2026-11-12), pela CLI oficial (kcadm.sh):
#   - auditoria pelo listener workspace-audit (sem dados pessoais) no lugar do jboss-logging padrão, que grava
#     username e IP no log; nada de eventos ou ações administrativas gravados no banco (guardariam o IP);
#   - proteção contra força bruta para os administradores;
#   - client de serviço "workspace-automation" (client credentials) para scripts e testes;
#   - admin-cli do master sem password grant (fluxo proibido pelo security.md); admins usam o Admin Console;
#   - reconcilia no realm workspace as configurações de auditoria/LGPD, reset de senha, SMTP, roles, fluxo de login
#     "workspace-browser" (OTP das roles log-investigator e admin), User Profile, textos traduzidos e o client "cadastro"
#     (o --import-realm não altera realm existente);
#   - login social só com Google (ADR-0009): fluxos de primeiro login e pós-broker sempre; o provedor "google" é
#     criado só com GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET; vínculo de conta pela própria sessão (idp_link) desligado.
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
    -s 'eventsListeners=["workspace-audit","legacy-migration-completion"]' \
    -s eventsEnabled=false \
    -s adminEventsEnabled=false \
    -s adminEventsDetailsEnabled=false \
    -s bruteForceProtected=true \
    -s resetPasswordAllowed=true \
    -s actionTokenGeneratedByAdminLifespan=43200 \
    -s registrationEmailAsUsername=false \
    -s 'smtpServer={"host":"mailpit","port":"1025","from":"no-reply@workspace.local","fromDisplayName":"Workspace","auth":"false","ssl":"false","starttls":"false"}' >/dev/null
  log "workspace realm: audit listener, no event storage, password reset, username policy, SMTP and default admin action-token lifespan (12h) reconciled"
  ensure_workspace_roles
  ensure_workspace_auth_flows
  kc update users/profile -r workspace -f "${PROVISIONING_DIR}/workspace-user-profile.json" >/dev/null
  kc create localization/pt-BR -r workspace -f "${PROVISIONING_DIR}/workspace-localization-pt-BR.json" >/dev/null
  kc create localization/en -r workspace -f "${PROVISIONING_DIR}/workspace-localization-en.json" >/dev/null
  log "workspace realm: user profile and localization texts applied"
  ensure_cadastro_client
  ensure_grafana_client
  ensure_cadastro_importer_client
  disable_session_account_linking
  ensure_google_login
}

# Roles do Cadastro (spec 001): "user" entra como role padrão de todo usuário novo.
# "log-investigator" (spec 001 do graylog) nunca é padrão: só por atribuição nominal, com OTP no login.
ensure_workspace_roles() {
  local role
  for role in admin user; do
    if ! kc get "roles/${role}" -r workspace >/dev/null 2>&1; then
      kc create roles -r workspace -s "name=${role}" >/dev/null
      log "workspace realm: role created: ${role}"
    fi
  done
  if ! kc get roles/log-investigator -r workspace >/dev/null 2>&1; then
    kc create roles -r workspace -s name=log-investigator >/dev/null
    log "workspace realm: role created: log-investigator"
  fi
  # Roles do Grafana (spec 001 do grafana, ADR-0002): nunca padrão, só por atribuição nominal (o usuário final do
  # Cadastro vive neste realm e não pode entrar no Grafana). O Admin do Grafana é a role "admin" (OTP já obrigatório).
  for role in grafana-viewer grafana-editor; do
    if ! kc get "roles/${role}" -r workspace >/dev/null 2>&1; then
      kc create roles -r workspace -s "name=${role}" >/dev/null
      log "workspace realm: role created: ${role}"
    fi
  done
  kc update roles/grafana-viewer -r workspace -s 'description=Grafana: acesso somente leitura (Viewer). Só por atribuição nominal.' >/dev/null
  kc update roles/grafana-editor -r workspace -s 'description=Grafana: edição de dashboards (Editor). Só por atribuição nominal.' >/dev/null
  for role in grafana-viewer grafana-editor; do
    if kc get roles/default-roles-workspace/composites/realm -r workspace --fields name --format csv --noquotes \
      | grep -qx "$role"; then
      kc remove-roles -r workspace --rname default-roles-workspace --rolename "$role" >/dev/null
      log "workspace realm: ${role} removed from default roles"
    fi
  done
  # Importação em lote (spec 002 do rabbitmq, ADR-0006): role do admin que envia o arquivo no Cadastro. Nunca padrão;
  # o OTP recente (acr=otp + auth_time) é exigido pelo Cadastro, como na role log-investigator.
  if ! kc get roles/user-importer -r workspace >/dev/null 2>&1; then
    kc create roles -r workspace -s name=user-importer >/dev/null
    log "workspace realm: role created: user-importer"
  fi
  kc update roles/user-importer -r workspace \
    -s 'description=Importação em lote de usuários no Cadastro; exige acr=otp recente. Só por atribuição nominal.' >/dev/null
  if kc get roles/default-roles-workspace/composites/realm -r workspace --fields name --format csv --noquotes \
    | grep -qx user-importer; then
    kc remove-roles -r workspace --rname default-roles-workspace --rolename user-importer >/dev/null
    log "workspace realm: user-importer removed from default roles"
  fi
  kc update roles/log-investigator -r workspace \
    -s 'description=Investigação em logs: gera consultas por token; OTP obrigatório.' >/dev/null
  kc add-roles -r workspace --rname default-roles-workspace --rolename user >/dev/null
  if kc get roles/default-roles-workspace/composites/realm -r workspace --fields name --format csv --noquotes \
    | grep -qx log-investigator; then
    kc remove-roles -r workspace --rname default-roles-workspace --rolename log-investigator >/dev/null
    log "workspace realm: log-investigator removed from default roles"
  fi
}

# Adiciona uma execução com exigência e prioridade explícitas; com $5/$6, também a configuração (alias e JSON).
# A prioridade vai no PUT: no Keycloak 26 o PUT só com {id,requirement} zera a prioridade e embaralha a ordem.
add_flow_execution() {
  local flow="$1" provider="$2" requirement="$3" priority="$4" config_alias="${5:-}" config="${6:-}" id
  id="$(kc create "authentication/flows/${flow}/executions/execution" -r workspace -s "provider=${provider}" -i)"
  kc update "authentication/flows/${flow}/executions" -r workspace -f - \
    <<<"{\"id\":\"${id}\",\"requirement\":\"${requirement}\",\"priority\":${priority}}" >/dev/null
  if [[ -n "$config_alias" ]]; then
    kc create "authentication/executions/${id}/config" -r workspace -f - \
      <<<"{\"alias\":\"${config_alias}\",\"config\":${config}}" >/dev/null
  fi
}

# Adiciona um subfluxo (o id devolvido pelo create é o do fluxo; a execução é buscada pelo alias).
add_subflow() {
  local parent="$1" alias="$2" description="$3" requirement="$4" priority="$5" id
  kc create "authentication/flows/${parent}/executions/flow" -r workspace -f - >/dev/null \
    <<<"{\"alias\":\"${alias}\",\"description\":\"${description}\",\"type\":\"basic-flow\",\"provider\":\"registration-page-form\"}"
  id="$(kc get "authentication/flows/${parent}/executions" -r workspace --fields id,displayName,level --format csv --noquotes \
    | grep ",${alias},0\$" | cut -d, -f1)"
  kc update "authentication/flows/${parent}/executions" -r workspace -f - \
    <<<"{\"id\":\"${id}\",\"requirement\":\"${requirement}\",\"priority\":${priority}}" >/dev/null
}

top_flow_id() {
  kc get authentication/flows -r workspace --fields id,alias --format csv --noquotes | grep ",$1\$" | cut -d, -f1 || true
}

# Estrutura comparável de um fluxo: nível, ordem, prioridade, nome, provider, exigência e configuração de cada execução.
flow_signature() {
  local line config_id
  kc get "authentication/flows/$1/executions" -r workspace \
    --fields level,index,priority,displayName,providerId,requirement,authenticationConfig --format csv --noquotes \
    | while IFS= read -r line; do
        config_id="${line##*,}"
        if [[ -n "$config_id" ]]; then
          line="${line%,*},$(kc get "authentication/config/${config_id}" -r workspace --fields 'alias,config(*)' </dev/null \
            | tr -d '[:space:]')"
        fi
        echo "$line"
      done
}

# Tira o sufixo de staging do alias de um subfluxo ou config ($1 = caminho na Admin API).
drop_staging_suffix() {
  kc get "$1" -r workspace | sed 's/\("alias" *: *"[^"]*\)-staging"/\1"/' | kc update "$1" -r workspace -f - >/dev/null
}

idp_exists() {
  kc get identity-provider/instances/google -r workspace --fields alias >/dev/null 2>&1
}

# Um "binding" é um atributo do realm ou, com o prefixo "idp:", um campo do provedor google. Sem o provedor, os fluxos
# existem, mas não há o que ligar (ensure_google_identity_provider os liga quando o cria).
bound_flow() {
  if [[ "$1" != idp:* ]]; then
    kc get realms/workspace --fields "$1" --format csv --noquotes
  elif idp_exists; then
    kc get identity-provider/instances/google -r workspace --fields "${1#idp:}" --format csv --noquotes
  fi
}

bind_flow() {
  if [[ "$1" != idp:* ]]; then
    kc update realms/workspace -s "$1=$2" >/dev/null
  elif idp_exists; then
    kc update identity-provider/instances/google -r workspace -s "${1#idp:}=$2" >/dev/null
  fi
}

# Solta um fluxo sem versão anterior. No provedor não há padrão seguro para onde voltar (o "first broker login" do
# Keycloak cria conta e "sem pós-broker" dispensa o OTP): o provedor é desligado antes.
release_flow() {
  if [[ "$1" != idp:* ]]; then
    bind_flow "$1" "$2"
  else
    kc update identity-provider/instances/google -r workspace -s enabled=false -s "${1#idp:}=" >/dev/null
    log "workspace realm: google identity provider disabled while its flow is rebuilt"
  fi
}

# Garante o fluxo $1 com a estrutura da função construtora $2, ligado ao binding $3 (ver bound_flow); $4 imprime a
# assinatura esperada. Também reconcilia fluxo alterado à mão no Admin Console. Nunca liga fluxo pela metade: constrói em
# "<alias>-staging" com subfluxos e configs também com "-staging" (não colidem com os do fluxo em uso), compara a
# assinatura e só então liga o staging, apaga a versão anterior (o delete leva subfluxos e configs) e tira o sufixo de
# tudo. Staging largado por execução interrompida é apagado. Mudança de estrutura: nova versão nos nomes (-v1- → -v2-).
ensure_flow() {
  local alias="$1" builder="$2" binding="$3" fallback="$5" expected staging="$1-staging" id flow_id config_id
  expected="$("$4")"
  id="$(top_flow_id "$alias")"
  if [[ -n "$id" && "$(flow_signature "$alias")" == "$expected" ]]; then
    bind_flow "$binding" "$alias"
    return 0
  fi
  id="$(top_flow_id "$staging")"
  if [[ -n "$id" ]]; then
    # Staging largado (execução interrompida ou alterado à mão) pode estar ligado ao realm: religa ao fluxo anterior ou
    # ao padrão do Keycloak ANTES de apagar, senão o delete falha ("currently in use") e o realm fica sem fluxo válido.
    if [[ "$(bound_flow "$binding")" == "$staging" ]]; then
      if [[ -n "$(top_flow_id "$alias")" ]]; then
        bind_flow "$binding" "$alias"
      else
        release_flow "$binding" "$fallback"
      fi
    fi
    kc delete "authentication/flows/${id}" -r workspace >/dev/null
    log "workspace realm: leftover flow removed: ${staging}"
  fi
  "$builder" "$staging" -staging
  if [[ "$(flow_signature "$staging" | sed 's/-staging//g')" != "$expected" ]]; then
    echo "Flow ${staging} does not match the expected structure; ${binding} left unchanged. Got:" >&2
    flow_signature "$staging" >&2
    exit 1
  fi
  bind_flow "$binding" "$staging"
  id="$(top_flow_id "$alias")"
  if [[ -n "$id" ]]; then
    kc delete "authentication/flows/${id}" -r workspace >/dev/null
  fi
  kc get "authentication/flows/${staging}/executions" -r workspace --fields flowId,authenticationConfig --format csv \
    --noquotes | while IFS=, read -r flow_id config_id; do
      if [[ -n "$flow_id" ]]; then drop_staging_suffix "authentication/flows/${flow_id}" </dev/null; fi
      if [[ -n "$config_id" ]]; then drop_staging_suffix "authentication/config/${config_id}" </dev/null; fi
    done
  drop_staging_suffix "authentication/flows/$(top_flow_id "$staging")"
  if [[ "$(flow_signature "$alias")" != "$expected" ]]; then
    echo "Flow ${alias} does not match the expected structure after promotion" >&2
    exit 1
  fi
  bind_flow "$binding" "$alias"
  log "workspace realm: flow ${alias} built (binding ${binding})"
}

# Login do navegador, igual ao realm-workspace.json: step-up por nível de autenticação (LoA 1 = senha, acr "password";
# LoA 2 = OTP nesta autenticação, acr "otp") e OTP obrigatório para log-investigator. O "Condition - credential" não
# pede o OTP duas vezes na mesma autenticação.
build_browser_flow() {
  local top="$1" s="$2"
  local forms="workspace-browser-v2-forms${s}"
  kc create authentication/flows -r workspace -f - >/dev/null \
    <<<"{\"alias\":\"${top}\",\"description\":\"Login do navegador: usuário e senha com migração do Legado, step-up por LoA e OTP obrigatório para log-investigator e admin.\",\"providerId\":\"basic-flow\",\"topLevel\":true,\"builtIn\":false}"
  add_flow_execution "$top" auth-cookie ALTERNATIVE 10
  add_subflow "$top" "$forms" "Formulários de login por nível de autenticação." ALTERNATIVE 20
  add_subflow "$forms" workspace-browser-v2-password${s} "LoA 1: usuário e senha." CONDITIONAL 10
  add_flow_execution workspace-browser-v2-password${s} conditional-level-of-authentication REQUIRED 10 \
    workspace-browser-v2-loa-password${s} '{"loa-condition-level":"1","loa-max-age":"36000"}'
  add_flow_execution workspace-browser-v2-password${s} legacy-username-password-form REQUIRED 20
  add_subflow "$forms" workspace-browser-v2-step-up-otp${s} "LoA 2 (acr otp): OTP nesta autenticação." CONDITIONAL 20
  add_flow_execution workspace-browser-v2-step-up-otp${s} conditional-level-of-authentication REQUIRED 10 \
    workspace-browser-v2-loa-otp${s} '{"loa-condition-level":"2","loa-max-age":"0"}'
  add_flow_execution workspace-browser-v2-step-up-otp${s} auth-otp-form REQUIRED 20
  add_subflow "$forms" workspace-browser-v2-otp-by-role${s} "OTP obrigatório para log-investigator." CONDITIONAL 30
  add_flow_execution workspace-browser-v2-otp-by-role${s} conditional-user-role REQUIRED 10 \
    workspace-browser-v2-role-log-investigator${s} '{"condUserRole":"log-investigator","negate":"false"}'
  add_flow_execution workspace-browser-v2-otp-by-role${s} conditional-credential REQUIRED 20 \
    workspace-browser-v2-otp-not-used-by-role${s} '{"credentials":"otp","included":"false"}'
  add_flow_execution workspace-browser-v2-otp-by-role${s} auth-otp-form REQUIRED 30
  add_subflow "$forms" workspace-browser-v2-otp-by-admin${s} "OTP obrigatório para admin (RN-12)." CONDITIONAL 40
  add_flow_execution workspace-browser-v2-otp-by-admin${s} conditional-user-role REQUIRED 10 \
    workspace-browser-v2-role-admin${s} '{"condUserRole":"admin","negate":"false"}'
  add_flow_execution workspace-browser-v2-otp-by-admin${s} conditional-credential REQUIRED 20 \
    workspace-browser-v2-otp-not-used-by-admin${s} '{"credentials":"otp","included":"false"}'
  add_flow_execution workspace-browser-v2-otp-by-admin${s} auth-otp-form REQUIRED 30
  add_subflow "$forms" workspace-browser-v2-otp-configured${s} "OTP para quem já o configurou (como no browser padrão)." CONDITIONAL 50
  add_flow_execution workspace-browser-v2-otp-configured${s} conditional-user-configured REQUIRED 10
  add_flow_execution workspace-browser-v2-otp-configured${s} conditional-credential REQUIRED 20 \
    workspace-browser-v2-otp-not-used-configured${s} '{"credentials":"otp","included":"false"}'
  add_flow_execution workspace-browser-v2-otp-configured${s} auth-otp-form REQUIRED 30
}

expected_browser_flow() {
  cat <<'SIGNATURE'
0,0,10,Cookie,auth-cookie,ALTERNATIVE,
0,1,20,workspace-browser-v2-forms,,ALTERNATIVE,
1,0,10,workspace-browser-v2-password,,CONDITIONAL,
2,0,10,Condition - Level of Authentication,conditional-level-of-authentication,REQUIRED,{"alias":"workspace-browser-v2-loa-password","config":{"loa-condition-level":"1","loa-max-age":"36000"}}
2,1,20,Legacy Username Password Form,legacy-username-password-form,REQUIRED,
1,1,20,workspace-browser-v2-step-up-otp,,CONDITIONAL,
2,0,10,Condition - Level of Authentication,conditional-level-of-authentication,REQUIRED,{"alias":"workspace-browser-v2-loa-otp","config":{"loa-condition-level":"2","loa-max-age":"0"}}
2,1,20,OTP Form,auth-otp-form,REQUIRED,
1,2,30,workspace-browser-v2-otp-by-role,,CONDITIONAL,
2,0,10,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-browser-v2-role-log-investigator","config":{"condUserRole":"log-investigator","negate":"false"}}
2,1,20,Condition - credential,conditional-credential,REQUIRED,{"alias":"workspace-browser-v2-otp-not-used-by-role","config":{"credentials":"otp","included":"false"}}
2,2,30,OTP Form,auth-otp-form,REQUIRED,
1,3,40,workspace-browser-v2-otp-by-admin,,CONDITIONAL,
2,0,10,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-browser-v2-role-admin","config":{"condUserRole":"admin","negate":"false"}}
2,1,20,Condition - credential,conditional-credential,REQUIRED,{"alias":"workspace-browser-v2-otp-not-used-by-admin","config":{"credentials":"otp","included":"false"}}
2,2,30,OTP Form,auth-otp-form,REQUIRED,
1,4,50,workspace-browser-v2-otp-configured,,CONDITIONAL,
2,0,10,Condition - user configured,conditional-user-configured,REQUIRED,
2,1,20,Condition - credential,conditional-credential,REQUIRED,{"alias":"workspace-browser-v2-otp-not-used-configured","config":{"credentials":"otp","included":"false"}}
2,2,30,OTP Form,auth-otp-form,REQUIRED,
SIGNATURE
}

# Reset de senha, igual ao realm-workspace.json: o reset conclui o login, então log-investigator confirma o OTP (ou
# configura um); os demais seguem o reset padrão do Keycloak (o OTP configurado é removido e reconfigurado).
build_reset_credentials_flow() {
  local top="$1" s="$2"
  kc create authentication/flows -r workspace -f - >/dev/null \
    <<<"{\"alias\":\"${top}\",\"description\":\"Reset de senha por e-mail; OTP obrigatório para log-investigator e admin.\",\"providerId\":\"basic-flow\",\"topLevel\":true,\"builtIn\":false}"
  add_flow_execution "$top" legacy-reset-choose-user REQUIRED 10
  add_flow_execution "$top" reset-credential-email REQUIRED 20
  add_flow_execution "$top" reset-password REQUIRED 30
  add_subflow "$top" workspace-reset-v3-otp-by-role${s} "OTP obrigatório para log-investigator." CONDITIONAL 40
  add_flow_execution workspace-reset-v3-otp-by-role${s} conditional-user-role REQUIRED 10 \
    workspace-reset-v3-role-log-investigator${s} '{"condUserRole":"log-investigator","negate":"false"}'
  add_flow_execution workspace-reset-v3-otp-by-role${s} auth-otp-form REQUIRED 20
  add_subflow "$top" workspace-reset-v3-otp-by-admin${s} "OTP obrigatório para admin que não é log-investigator (este já confirma o OTP acima)." CONDITIONAL 50
  add_flow_execution workspace-reset-v3-otp-by-admin${s} conditional-user-role REQUIRED 10 \
    workspace-reset-v3-role-admin${s} '{"condUserRole":"admin","negate":"false"}'
  add_flow_execution workspace-reset-v3-otp-by-admin${s} conditional-user-role REQUIRED 20 \
    workspace-reset-v3-admin-not-log-investigator${s} '{"condUserRole":"log-investigator","negate":"true"}'
  add_flow_execution workspace-reset-v3-otp-by-admin${s} auth-otp-form REQUIRED 30
  add_subflow "$top" workspace-reset-v3-otp-configured${s} "Reset padrão do OTP configurado, exceto log-investigator e admin." CONDITIONAL 60
  add_flow_execution workspace-reset-v3-otp-configured${s} conditional-user-configured REQUIRED 10
  add_flow_execution workspace-reset-v3-otp-configured${s} conditional-user-role REQUIRED 20 \
    workspace-reset-v3-not-log-investigator${s} '{"condUserRole":"log-investigator","negate":"true"}'
  add_flow_execution workspace-reset-v3-otp-configured${s} conditional-user-role REQUIRED 30 \
    workspace-reset-v3-not-admin${s} '{"condUserRole":"admin","negate":"true"}'
  add_flow_execution workspace-reset-v3-otp-configured${s} reset-otp REQUIRED 40
}

expected_reset_credentials_flow() {
  cat <<'SIGNATURE'
0,0,10,Legacy Choose User,legacy-reset-choose-user,REQUIRED,
0,1,20,Send Reset Email,reset-credential-email,REQUIRED,
0,2,30,Reset Password,reset-password,REQUIRED,
0,3,40,workspace-reset-v3-otp-by-role,,CONDITIONAL,
1,0,10,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-reset-v3-role-log-investigator","config":{"condUserRole":"log-investigator","negate":"false"}}
1,1,20,OTP Form,auth-otp-form,REQUIRED,
0,4,50,workspace-reset-v3-otp-by-admin,,CONDITIONAL,
1,0,10,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-reset-v3-role-admin","config":{"condUserRole":"admin","negate":"false"}}
1,1,20,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-reset-v3-admin-not-log-investigator","config":{"condUserRole":"log-investigator","negate":"true"}}
1,2,30,OTP Form,auth-otp-form,REQUIRED,
0,5,60,workspace-reset-v3-otp-configured,,CONDITIONAL,
1,0,10,Condition - user configured,conditional-user-configured,REQUIRED,
1,1,20,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-reset-v3-not-log-investigator","config":{"condUserRole":"log-investigator","negate":"true"}}
1,2,30,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-reset-v3-not-admin","config":{"condUserRole":"admin","negate":"true"}}
1,3,40,Reset OTP,reset-otp,REQUIRED,
SIGNATURE
}

# Step-up no realm: acr "password" = LoA 1 e "otp" = LoA 2 (contrato com o Cadastro, ver README).
ensure_workspace_auth_flows() {
  kc update realms/workspace -f - >/dev/null <<<'{"attributes":{"acr.loa.map":"{\"password\":1,\"otp\":2}"}}'
  ensure_flow workspace-browser build_browser_flow browserFlow expected_browser_flow browser
  ensure_flow workspace-reset-credentials build_reset_credentials_flow resetCredentialsFlow expected_reset_credentials_flow "reset credentials"
}

# Primeiro login com o Google (ADR-0009): só vincula a uma conta que já existe e só depois da senha dessa conta. Nunca
# cria usuário (sem idp-create-user-if-unique) nem vincula sozinho por e-mail (sem idp-auto-link).
build_first_broker_login_flow() {
  local top="$1"
  kc create authentication/flows -r workspace -f - >/dev/null \
    <<<"{\"alias\":\"${top}\",\"description\":\"Primeiro login com provedor externo: só conta existente, vinculada após a senha dela.\",\"providerId\":\"basic-flow\",\"topLevel\":true,\"builtIn\":false}"
  add_flow_execution "$top" idp-detect-existing-broker-user REQUIRED 10
  add_flow_execution "$top" idp-username-password-form REQUIRED 20
}

expected_first_broker_login_flow() {
  cat <<'SIGNATURE'
0,0,10,Detect existing broker user,idp-detect-existing-broker-user,REQUIRED,
0,1,20,Username Password Form for identity provider reauthentication,idp-username-password-form,REQUIRED,
SIGNATURE
}

# Depois de todo login com o Google: OTP para as roles privilegiadas (sem OTP
# cadastrado, configura antes de concluir) e para quem já o configurou. Sem condição de LoA: nunca produz acr=otp.
build_post_broker_login_flow() {
  local top="$1" s="$2" role subflow priority=10
  kc create authentication/flows -r workspace -f - >/dev/null \
    <<<"{\"alias\":\"${top}\",\"description\":\"Pós-login com provedor externo: OTP para roles privilegiadas e para quem já o configurou.\",\"providerId\":\"basic-flow\",\"topLevel\":true,\"builtIn\":false}"
  for role in admin log-investigator user-importer; do
    subflow="workspace-broker-v1-otp-by-${role}${s}"
    add_subflow "$top" "$subflow" "OTP obrigatório para ${role}." CONDITIONAL "$priority"
    add_flow_execution "$subflow" conditional-user-role REQUIRED 10 \
      "workspace-broker-v1-role-${role}${s}" "{\"condUserRole\":\"${role}\",\"negate\":\"false\"}"
    add_flow_execution "$subflow" conditional-credential REQUIRED 20 \
      "workspace-broker-v1-otp-not-used-by-${role}${s}" '{"credentials":"otp","included":"false"}'
    add_flow_execution "$subflow" auth-otp-form REQUIRED 30
    priority=$((priority + 10))
  done
  subflow="workspace-broker-v1-otp-configured${s}"
  add_subflow "$top" "$subflow" "OTP para quem já o configurou." CONDITIONAL "$priority"
  add_flow_execution "$subflow" conditional-user-configured REQUIRED 10
  add_flow_execution "$subflow" conditional-credential REQUIRED 20 \
    "workspace-broker-v1-otp-not-used-configured${s}" '{"credentials":"otp","included":"false"}'
  add_flow_execution "$subflow" auth-otp-form REQUIRED 30
  # Sem este passo, quem não cai em nenhum subflow condicional é negado: o fluxo não teria execução bem-sucedida.
  add_flow_execution "$top" allow-access-authenticator REQUIRED 50
}

expected_post_broker_login_flow() {
  local role index=0
  for role in admin log-investigator user-importer; do
    cat <<SIGNATURE
0,${index},$((index * 10 + 10)),workspace-broker-v1-otp-by-${role},,CONDITIONAL,
1,0,10,Condition - user role,conditional-user-role,REQUIRED,{"alias":"workspace-broker-v1-role-${role}","config":{"condUserRole":"${role}","negate":"false"}}
1,1,20,Condition - credential,conditional-credential,REQUIRED,{"alias":"workspace-broker-v1-otp-not-used-by-${role}","config":{"credentials":"otp","included":"false"}}
1,2,30,OTP Form,auth-otp-form,REQUIRED,
SIGNATURE
    index=$((index + 1))
  done
  cat <<'SIGNATURE'
0,3,40,workspace-broker-v1-otp-configured,,CONDITIONAL,
1,0,10,Condition - user configured,conditional-user-configured,REQUIRED,
1,1,20,Condition - credential,conditional-credential,REQUIRED,{"alias":"workspace-broker-v1-otp-not-used-configured","config":{"credentials":"otp","included":"false"}}
1,2,30,OTP Form,auth-otp-form,REQUIRED,
0,4,50,Allow access,allow-access-authenticator,REQUIRED,
SIGNATURE
}

# O kc_action=idp_link vincula um provedor externo só com a sessão aberta, sem pedir a senha (ADR-0009): desligado.
# O vínculo só nasce no primeiro login pelo provedor, que exige a senha da conta.
disable_session_account_linking() {
  if kc get authentication/required-actions/idp_link -r workspace >/dev/null 2>&1; then
    kc update authentication/required-actions/idp_link -r workspace -s enabled=false >/dev/null
    log "workspace realm: required action idp_link disabled"
  fi
}

# Provedor google a partir de workspace-google-idp.json, sem mappers (nada do Google altera atributos da conta). Sem as
# credenciais (CI), ele não é criado; se já existe, é reconciliado mantendo client id e segredo gravados. $1 é o estado
# "enabled" anterior: um provedor desligado pelo administrador continua desligado. Os segredos vão pela entrada padrão e
# nunca para o log.
ensure_google_identity_provider() {
  local enabled="${1:-true}" client_id="${GOOGLE_CLIENT_ID:-}" client_secret="${GOOGLE_CLIENT_SECRET:-}"
  local body mapper_id credential_pattern='^[A-Za-z0-9._-]+$'
  if [[ -z "$client_id" || -z "$client_secret" ]]; then
    if ! idp_exists; then
      log "workspace realm: GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET not set; google identity provider not provisioned"
      return 0
    fi
    client_id="$(kc get identity-provider/instances/google -r workspace \
      | sed -n 's/.*"clientId" *: *"\([^"]*\)".*/\1/p')"
    # Valor mascarado: o Keycloak mantém o segredo que já está gravado.
    client_secret='**********'
    log "workspace realm: GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET not set; keeping the stored google credentials"
  elif [[ ! "$client_id" =~ $credential_pattern || ! "$client_secret" =~ $credential_pattern ]]; then
    echo "GOOGLE_CLIENT_ID or GOOGLE_CLIENT_SECRET has unexpected characters (check quotes and spaces)" >&2
    exit 1
  fi
  body="$(<"${PROVISIONING_DIR}/workspace-google-idp.json")"
  body="${body//__GOOGLE_CLIENT_ID__/"$client_id"}"
  body="${body//__GOOGLE_CLIENT_SECRET__/"$client_secret"}"
  body="${body/'"enabled": true'/"\"enabled\": ${enabled}"}"
  if idp_exists; then
    kc update identity-provider/instances/google -r workspace -f - >/dev/null <<<"$body"
  else
    kc create identity-provider/instances -r workspace -f - >/dev/null <<<"$body"
    log "workspace realm: google identity provider created"
  fi
  for mapper_id in $(kc get identity-provider/instances/google/mappers -r workspace --fields id --format csv --noquotes); do
    kc delete "identity-provider/instances/google/mappers/${mapper_id}" -r workspace >/dev/null
    log "workspace realm: google identity provider: mapper removed"
  done
  log "workspace realm: google identity provider reconciled (enabled=${enabled})"
}

ensure_google_login() {
  local enabled=""
  # Lido antes dos fluxos: a reconstrução pode desligar o provedor por um instante.
  if idp_exists; then
    enabled="$(kc get identity-provider/instances/google -r workspace --fields enabled --format csv --noquotes)"
  fi
  ensure_flow workspace-first-broker-login build_first_broker_login_flow idp:firstBrokerLoginFlowAlias \
    expected_first_broker_login_flow ""
  ensure_flow workspace-post-broker-login build_post_broker_login_flow idp:postBrokerLoginFlowAlias \
    expected_post_broker_login_flow ""
  ensure_google_identity_provider "$enabled"
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
  printf '[%s,%s,%s,%s]' "$(kc get roles/admin -r workspace)" "$(kc get roles/user -r workspace)" \
    "$(kc get roles/log-investigator -r workspace)" "$(kc get roles/user-importer -r workspace)" \
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
  # Minimização (LGPD): tokens só com id, login, roles e origem da conta. Fora os scopes padrão que trazem nome, e-mail etc.
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
  # Origem da conta (claim account_origin = imported | migrated), só neste client. Deriva de importLineId/legacyUserId,
  # que o próprio usuário não edita (User Profile); o valor dos atributos nunca vai para o token.
  if ! kc get "clients/${id}/protocol-mappers/models" -r workspace --fields name --format csv --noquotes \
    | grep -qx account-origin; then
    kc create "clients/${id}/protocol-mappers/models" -r workspace -f - >/dev/null <<'JSON'
{"name":"account-origin","protocol":"openid-connect","protocolMapper":"workspace-account-origin-mapper","config":{"claim.name":"account_origin","id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"true","introspection.token.claim":"true"}}
JSON
    log "workspace realm: cadastro: mapper created: account-origin"
  fi
  log "workspace realm: client cadastro reconciled"
}

# SSO do Grafana (ADR-0002 do grafana): client confidencial, Authorization Code + PKCE S256, sem Direct Grant nem
# implícito, redirect e post-logout exatos. Mínimo privilégio: fullScopeAllowed=false e escopo só com grafana-viewer,
# grafana-editor e admin; o mapper "roles" leva as realm roles (já filtradas pelo escopo) ao claim "roles".
# Mantém profile/email nos scopes padrão: o Grafana exige e-mail/login para criar o usuário; o resto é removido.
ensure_grafana_client() {
  : "${KC_GRAFANA_CLIENT_SECRET:?KC_GRAFANA_CLIENT_SECRET is required (run scripts/generate-env.sh)}"
  local id body scopes scope_name
  id="$(workspace_client_id grafana)"
  body="$(printf '{"clientId":"grafana","name":"Grafana","description":"Grafana OSS: login OIDC (Authorization Code + PKCE). Roles grafana-viewer, grafana-editor e admin no claim roles.","enabled":true,"publicClient":false,"clientAuthenticatorType":"client-secret","protocol":"openid-connect","standardFlowEnabled":true,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":false,"frontchannelLogout":false,"fullScopeAllowed":false,"redirectUris":["http://localhost:3000/login/generic_oauth"],"webOrigins":[],"attributes":{"pkce.code.challenge.method":"S256","post.logout.redirect.uris":"http://localhost:3000/login"},"secret":"%s"}' \
    "$KC_GRAFANA_CLIENT_SECRET")"
  if [[ -z "$id" ]]; then
    kc create clients -r workspace -f - <<<"$body" >/dev/null
    id="$(workspace_client_id grafana)"
    log "workspace realm: client created: grafana"
  else
    kc update "clients/${id}" -r workspace -f - <<<"$body" >/dev/null
  fi
  printf '[%s,%s,%s]' "$(kc get roles/admin -r workspace)" "$(kc get roles/grafana-viewer -r workspace)" \
    "$(kc get roles/grafana-editor -r workspace)" \
    | kc create "clients/${id}/scope-mappings/realm" -r workspace -f - >/dev/null
  scopes="$(kc get client-scopes -r workspace --fields id,name --format csv --noquotes)"
  for scope_name in basic acr roles profile email; do
    kc update "clients/${id}/default-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null
  done
  for scope_name in web-origins address phone microprofile-jwt organization; do
    kc delete "clients/${id}/default-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null 2>&1 || true
  done
  if ! kc get "clients/${id}/protocol-mappers/models" -r workspace --fields name --format csv --noquotes | grep -qx roles; then
    kc create "clients/${id}/protocol-mappers/models" -r workspace -f - >/dev/null <<'JSON'
{"name":"roles","protocol":"openid-connect","protocolMapper":"oidc-usermodel-realm-role-mapper","config":{"claim.name":"roles","multivalued":"true","jsonType.label":"String","id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"true"}}
JSON
  fi
  log "workspace realm: client grafana reconciled"
}

# Importador em lote do Cadastro (spec 002 do rabbitmq, ADR-0006): client confidencial só com conta de serviço
# (client credentials), sem fluxo de usuário. Menor privilégio: fullScopeAllowed=false, nenhuma realm role no escopo e
# só view-users e manage-users do realm-management (nada de manage-realm, manage-clients ou query-users). Cria usuário
# sem senha; o e-mail de ativação (execute-actions-email) usa o SMTP do realm e recebe o lifespan de 72 h por
# chamada (?lifespan=259200); o lifespan do realm fica no padrão do Keycloak (12 h) para não alongar os demais e-mails de admin.
ensure_cadastro_importer_client() {
  : "${KC_CADASTRO_IMPORTER_CLIENT_SECRET:?KC_CADASTRO_IMPORTER_CLIENT_SECRET is required (run scripts/generate-env.sh)}"
  local id body realm_management_id role scopes scope_name sa_id extra_roles
  id="$(workspace_client_id cadastro-importer)"
  body="$(printf '{"clientId":"cadastro-importer","name":"Cadastro importer","description":"Importação em lote do Cadastro: só conta de serviço (client credentials) para criar usuários sem senha.","enabled":true,"publicClient":false,"clientAuthenticatorType":"client-secret","protocol":"openid-connect","standardFlowEnabled":false,"implicitFlowEnabled":false,"directAccessGrantsEnabled":false,"serviceAccountsEnabled":true,"frontchannelLogout":false,"fullScopeAllowed":false,"redirectUris":[],"webOrigins":[],"secret":"%s"}' \
    "$KC_CADASTRO_IMPORTER_CLIENT_SECRET")"
  if [[ -z "$id" ]]; then
    kc create clients -r workspace -f - <<<"$body" >/dev/null
    id="$(workspace_client_id cadastro-importer)"
    log "workspace realm: client created: cadastro-importer"
  else
    kc update "clients/${id}" -r workspace -f - <<<"$body" >/dev/null
  fi
  realm_management_id="$(workspace_client_id realm-management)"
  for role in view-users manage-users; do
    printf '[%s]' "$(kc get "clients/${realm_management_id}/roles/${role}" -r workspace)" \
      | kc create "clients/${id}/scope-mappings/clients/${realm_management_id}" -r workspace -f - >/dev/null
  done
  kc add-roles -r workspace --uusername service-account-cadastro-importer --cclientid realm-management \
    --rolename view-users --rolename manage-users >/dev/null
  # Reconcilia para o conjunto exato: remove da conta de serviço qualquer outra role do realm-management.
  sa_id="$(kc get "clients/${id}/service-account-user" -r workspace --fields id --format csv --noquotes)"
  extra_roles="$(kc get "users/${sa_id}/role-mappings/clients/${realm_management_id}" -r workspace --fields name --format csv --noquotes \
    | grep -vx -e view-users -e manage-users || true)"
  for role in $extra_roles; do
    kc remove-roles -r workspace --uusername service-account-cadastro-importer --cclientid realm-management --rolename "$role" >/dev/null
    log "workspace realm: cadastro-importer: extra role removed from service account: ${role}"
  done
  # Audiência da Admin API só quando pedida (scope=kc-admin-api). Fica "roles" (leva o realm-management ao token) e
  # saem os scopes que trazem nome, e-mail etc. (LGPD).
  scopes="$(kc get client-scopes -r workspace --fields id,name --format csv --noquotes)"
  kc update "clients/${id}/optional-client-scopes/$(grep ',kc-admin-api$' <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null
  for scope_name in basic acr roles; do
    kc update "clients/${id}/default-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null
  done
  for scope_name in profile email web-origins address phone microprofile-jwt organization; do
    kc delete "clients/${id}/default-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null 2>&1 || true
    kc delete "clients/${id}/optional-client-scopes/$(grep ",${scope_name}\$" <<<"$scopes" | cut -d, -f1)" -r workspace >/dev/null 2>&1 || true
  done
  log "workspace realm: client cadastro-importer reconciled"
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
