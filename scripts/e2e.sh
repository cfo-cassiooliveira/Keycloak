#!/usr/bin/env bash
# Testes E2E do tema de login (Playwright em navegador real) contra o Keycloak do docker compose.
# Roda no container oficial do Playwright: a máquina só precisa do Docker.
# Uso (na raiz do projeto, com o stack de pé): ./scripts/e2e.sh [argumentos do "playwright test"]
set -euo pipefail

PLAYWRIGHT_IMAGE="mcr.microsoft.com/playwright:v1.64.1-noble@sha256:2f05f8a734509a7b3f26a912639488ee08f58a7b292d14d62271bcff9038768f"
project_root="$(cd "$(dirname "$0")/.." && pwd)"
# No Git Bash (Windows) o Docker precisa do caminho no formato C:/...; "pwd -W" só existe lá.
host_dir="$(cd "$project_root" && (pwd -W 2>/dev/null || pwd))"

if ! curl -fs http://127.0.0.1:9100/health/ready 2>/dev/null | grep -q '"UP"'; then
  echo "Keycloak não está pronto em 127.0.0.1:9100. Suba o ambiente: docker compose up -d --build" >&2
  exit 2
fi

# node_modules fica num volume Docker (não polui o repositório); npm ci respeita o package-lock.json.
MSYS_NO_PATHCONV=1 docker run --rm --ipc=host \
  -v "${host_dir}/e2e:/e2e" \
  -v keycloak-e2e-node-modules:/e2e/node_modules \
  -w /e2e \
  -e E2E_FORWARD_TO=host.docker.internal:8180 \
  "$PLAYWRIGHT_IMAGE" \
  bash -c 'npm ci --no-audit --no-fund --loglevel=error && npx playwright test "$@"' -- "$@"
