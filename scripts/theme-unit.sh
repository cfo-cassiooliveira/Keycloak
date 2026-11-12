#!/usr/bin/env bash
# Testes unitários do JavaScript do tema (Vitest + jsdom) com cobertura lcov para o SonarQube.
# Não precisa do Keycloak de pé. Roda no container do Playwright (Node fixado por digest): só o Docker é necessário.
# O projeto é montado em /workspace, o mesmo caminho da análise do SonarQube, para o lcov apontar para os fontes.
# Uso: ./scripts/theme-unit.sh
set -euo pipefail

NODE_IMAGE="mcr.microsoft.com/playwright:v1.64.1-noble@sha256:2f05f8a734509a7b3f26a912639488ee08f58a7b292d14d62271bcff9038768f"
project_root="$(cd "$(dirname "$0")/.." && pwd)"
host_dir="$(cd "$project_root" && (pwd -W 2>/dev/null || pwd))"

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${host_dir}:/workspace" \
  -v keycloak-theme-tests-node-modules:/workspace/theme-tests/node_modules \
  -w /workspace/theme-tests \
  "$NODE_IMAGE" \
  bash -c 'npm ci --no-audit --no-fund --loglevel=error && npx vitest run --coverage'
