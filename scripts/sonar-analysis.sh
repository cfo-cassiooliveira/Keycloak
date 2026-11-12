#!/usr/bin/env bash
# Análise SonarQube do Keycloak (Java + cobertura JaCoCo) que falha se o Quality Gate reprovar.
# Roda no mesmo container Maven + JDK 21 do build (ADR-0001): build, testes, cobertura e análise numa execução.
#
# Local (SonarQube do projeto ../sonarqube de pé): ./scripts/sonar-analysis.sh
# Padrões locais: SONAR_HOST_URL=http://host.docker.internal:9001 e token em ../sonarqube/.tokens/keycloak.token.
# No CI, SONAR_HOST_URL e SONAR_TOKEN chegam pelos segredos do repositório.
set -euo pipefail

MAVEN_IMAGE="maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320"
SONAR_MAVEN_PLUGIN="org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211"
PROJECT_KEY="keycloak"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://host.docker.internal:9001}"

project_root="$(cd "$(dirname "$0")/.." && pwd)"
host_dir="$(cd "$project_root" && (pwd -W 2>/dev/null || pwd))"

if [[ -z "${SONAR_TOKEN:-}" ]]; then
  token_file="${project_root}/../sonarqube/.tokens/${PROJECT_KEY}.token"
  if [[ ! -s "$token_file" ]]; then
    echo "SONAR_TOKEN is not set and ${token_file} was not found. Start ../sonarqube and run its provisioner." >&2
    exit 2
  fi
  SONAR_TOKEN="$(cat "$token_file")"
fi
# O token vai por variável de ambiente herdada (-e SONAR_TOKEN), nunca na linha de comando do docker run.
export SONAR_TOKEN

MSYS_NO_PATHCONV=1 docker run --rm \
  -e SONAR_TOKEN \
  -v "${host_dir}:/workspace" \
  -v keycloak-m2:/root/.m2 \
  -w /workspace \
  "$MAVEN_IMAGE" \
  mvn -B verify "${SONAR_MAVEN_PLUGIN}:sonar" \
    -Dsonar.host.url="${SONAR_HOST_URL}" \
    -Dsonar.projectKey="${PROJECT_KEY}" \
    -Dsonar.projectName="Keycloak" \
    -Dsonar.qualitygate.wait=true \
    -Dsonar.qualitygate.timeout=300
