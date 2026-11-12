#!/usr/bin/env bash
# Gera o .env local com segredos aleatórios (nunca versionado). Não sobrescreve um .env existente.
# Uso: ./scripts/generate-env.sh            (mostra onde está a senha do admin, sem imprimi-la)
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  echo ".env já existe; nada foi alterado. Apague-o para gerar novos segredos."
  exit 0
fi

random() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"; }

umask 077
cat > .env <<EOF
# Gerado por scripts/generate-env.sh em $(date -u +%Y-%m-%dT%H:%M:%SZ). NUNCA versione este arquivo.
KC_DB_PASSWORD=$(random 32)
KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin
KC_BOOTSTRAP_ADMIN_PASSWORD=$(random 32)
EOF

echo ".env criado. Admin temporário: bootstrap-admin — senha em KC_BOOTSTRAP_ADMIN_PASSWORD no .env."
