package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.util.JsonSerialization;

import com.fasterxml.jackson.databind.JsonNode;

/** Usa os vetores do Legado (ADR-0005), copiados de hmac-test-vectors.json; a chave do arquivo é fictícia. */
class LegadoRequestSignerTest {

    private static final Pattern HEADER = Pattern.compile(
            "^keyId=([A-Za-z0-9._-]+), t=(\\d+), n=([A-Za-z0-9_-]{22}), v1=([A-Za-z0-9+/]+=*)$");

    private static JsonNode vectors() throws IOException {
        try (InputStream in = LegadoRequestSignerTest.class.getResourceAsStream("/hmac-test-vectors.json")) {
            return JsonSerialization.readValue(in, JsonNode.class);
        }
    }

    @Test
    void sign_WithLegadoVectors_ProducesTheSameSignature() throws IOException {
        JsonNode root = vectors();
        LegadoRequestSigner signer = new LegadoRequestSigner("keycloak-1",
                Base64.getDecoder().decode(root.get("secret").asText()), Clock.systemUTC());

        assertThat(root.get("vectors")).hasSize(3);
        for (JsonNode vector : root.get("vectors")) {
            String header = signer.sign(vector.get("timestamp").asLong(), vector.get("nonce").asText(),
                    vector.get("method").asText(), vector.get("pathAndQuery").asText(),
                    vector.get("body").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            assertThat(header).isEqualTo("keyId=keycloak-1, t=" + vector.get("timestamp").asLong() + ", n="
                    + vector.get("nonce").asText() + ", v1=" + vector.get("signature").asText());
        }
    }

    @Test
    void sign_UsesClockAndANewRandomBase64UrlNonceOnEveryCall() throws IOException {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_763_035_200L), ZoneOffset.UTC);
        LegadoRequestSigner signer = new LegadoRequestSigner("keycloak-1",
                Base64.getDecoder().decode(vectors().get("secret").asText()), clock);
        Set<String> nonces = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            Matcher matcher = HEADER.matcher(signer.sign("post", "/api/v1/internal/user-lookups", new byte[0]));

            assertThat(matcher.matches()).isTrue();
            assertThat(matcher.group(2)).isEqualTo("1763035200");
            nonces.add(matcher.group(3));
        }
        assertThat(nonces).hasSize(50);
    }

    @ParameterizedTest
    @ValueSource(strings = {"post", "POST", "Post"})
    void sign_MethodIsCanonicalizedToUpperCase(String method) {
        LegadoRequestSigner signer = new LegadoRequestSigner("keycloak-1", new byte[32], Clock.systemUTC());

        assertThat(signer.sign(10L, "AAAAAAAAAAAAAAAAAAAAAA", method, "/x", new byte[0]))
                .isEqualTo(signer.sign(10L, "AAAAAAAAAAAAAAAAAAAAAA", "POST", "/x", new byte[0]));
    }

    @Test
    void toString_DoesNotExposeTheKey() {
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 'k');
        LegadoRequestSigner signer = new LegadoRequestSigner("keycloak-1", secret, Clock.systemUTC());

        assertThat(signer.toString()).isEqualTo("LegadoRequestSigner[keyId=keycloak-1]")
                .doesNotContain("kkkk").doesNotContain(Base64.getEncoder().encodeToString(secret));
    }
}
