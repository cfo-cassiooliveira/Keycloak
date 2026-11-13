#!/usr/bin/env bash
# Cria a rede Docker compartilhada "workspace-identity" se ainda não existir (idempotente).
# O mesmo script existe no keycloak, no legado e no cadastro: nenhum projeto depende de outro estar de pé para subir
# (spec 001 do cadastro, RN-14). Uso: ./scripts/identity-network.sh
set -euo pipefail

NETWORK_NAME="workspace-identity"

if docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
  echo "Rede ${NETWORK_NAME} já existe."
else
  docker network create "$NETWORK_NAME" >/dev/null
  echo "Rede ${NETWORK_NAME} criada."
fi
