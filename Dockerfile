# Imagem otimizada do Keycloak do Workspace com as extensões deste projeto (ADR-0001).
# Os testes não rodam aqui: o portão de qualidade é scripts/build.sh, executado antes do build da imagem.

# 1) Empacota o JAR das extensões com JDK 21.
FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS extensions
WORKDIR /build
COPY pom.xml .
COPY realm realm
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# 2) Copia o JAR para providers/ e aplica as opções de build (kc.sh build).
#    Cria data/log (dono: keycloak) para o volume do fallback de auditoria nascer gravável.
FROM quay.io/keycloak/keycloak:26.8.2@sha256:723b1d6e254c0fc96d5cffec860c7e0744d6a3277822b865bb538cf891575e41 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_CACHE=local
COPY --from=extensions /build/target/workspace-keycloak-extensions-*.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build \
    && mkdir -p /opt/keycloak/data/log

# 3) Imagem final já otimizada: o start não recompila (start --optimized).
FROM quay.io/keycloak/keycloak:26.8.2@sha256:723b1d6e254c0fc96d5cffec860c7e0744d6a3277822b865bb538cf891575e41
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
