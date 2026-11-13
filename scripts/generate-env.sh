#!/usr/bin/env bash
# Gera o .env local com segredos aleatórios (nunca versionado). Idempotente: num .env existente só acrescenta as chaves
# que faltam (versões novas do projeto), sem trocar os segredos já em uso.
# Uso: ./scripts/generate-env.sh            (mostra onde está a senha do admin, sem imprimi-la)
set -euo pipefail

cd "$(dirname "$0")/.."

random() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"; }

umask 077
if [[ ! -f .env ]]; then
  echo "# Gerado por scripts/generate-env.sh em $(date -u +%Y-%m-%dT%H:%M:%SZ). NUNCA versione este arquivo." > .env
  created=true
else
  created=false
fi

ensure_key() {
  local key="$1" value="$2"
  if ! grep -q "^${key}=" .env; then
    echo "${key}=${value}" >> .env
    echo "Chave adicionada ao .env: ${key}"
  fi
}

ensure_key KC_DB_PASSWORD "$(random 32)"
ensure_key KC_BOOTSTRAP_ADMIN_USERNAME bootstrap-admin
ensure_key KC_BOOTSTRAP_ADMIN_PASSWORD "$(random 32)"
# Segredo do client de serviço "workspace-automation" (provisionamento, smoke tests e CI; client credentials).
ensure_key KC_AUTOMATION_CLIENT_SECRET "$(random 40)"
# Segredo do client confidencial "cadastro" (BFF do Cadastro). O mesmo valor vai para o .env do cadastro.
ensure_key KC_CADASTRO_CLIENT_SECRET "$(random 40)"

if [[ "$created" == true ]]; then
  echo ".env criado. Admin temporário: bootstrap-admin — senha em KC_BOOTSTRAP_ADMIN_PASSWORD no .env."
fi
