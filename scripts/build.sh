#!/usr/bin/env bash
# Compila e testa as extensões no container Maven + JDK 21 (ADR-0001): a máquina só precisa do Docker.
# Uso: scripts/build.sh [goals/opções do Maven]   (padrão: -B verify)
# O cache do Maven fica no volume Docker "keycloak-m2" para não baixar dependências a cada build.
set -euo pipefail

MAVEN_IMAGE="maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320"
project_root="$(cd "$(dirname "$0")/.." && pwd)"
# No Git Bash (Windows) o Docker precisa do caminho no formato C:/...; "pwd -W" só existe lá.
host_dir="$(cd "$project_root" && (pwd -W 2>/dev/null || pwd))"

if [[ $# -eq 0 ]]; then
  set -- -B verify
fi

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${host_dir}:/workspace" \
  -v keycloak-m2:/root/.m2 \
  -w /workspace \
  "$MAVEN_IMAGE" \
  mvn "$@"
