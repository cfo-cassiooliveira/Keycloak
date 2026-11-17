package com.workspace.keycloak.legacy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.keycloak.Config;

/**
 * Configuração do cliente da API interna do Legado.
 *
 * <p>O {@code signingKey} é a chave HMAC de 256 bits emitida pelo Legado (decodificada de Base64, como o Legado faz).
 * {@code allowInsecureHttp} existe só para testes: o tipo é restrito ao pacote e a opção nunca vem da
 * configuração da SPI. Ela libera {@code http://} e dispensa a CA. {@link #toString()} nunca mostra a chave.</p>
 */
record LegacyClientSettings(URI baseUrl, Duration timeout, String keyId, byte[] signingKey, Path caFile,
        boolean allowInsecureHttp) {

    /** Opções da SPI (env {@code KC_SPI_AUTHENTICATOR__LEGACY_USERNAME_PASSWORD_FORM__<OPÇÃO>}, hífens viram _). */
    public static final String OPTION_BASE_URL = "base-url";
    public static final String OPTION_TIMEOUT_MS = "timeout-ms";
    public static final String OPTION_KEY_ID = "key-id";
    public static final String OPTION_SIGNING_KEY = "signing-key";
    public static final String OPTION_CA_FILE = "ca-file";

    public static final int DEFAULT_TIMEOUT_MS = 2000;

    private static final int MIN_KEY_BYTES = 32;
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    LegacyClientSettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(signingKey, "signingKey");
        String rawPath = baseUrl.getRawPath();
        if (baseUrl.getHost() == null || baseUrl.getRawQuery() != null || baseUrl.getRawUserInfo() != null
                || !(rawPath == null || rawPath.isEmpty() || "/".equals(rawPath))) {
            throw new IllegalArgumentException(
                    "base-url must be an absolute URL with host only, such as https://legado-api:8443");
        }
        String scheme = baseUrl.getScheme() == null ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme)) {
            if (!allowInsecureHttp) {
                throw new IllegalArgumentException(
                        "base-url must use https: credentials must never travel in clear text");
            }
        } else if ("https".equals(scheme)) {
            if (caFile == null) {
                throw new IllegalArgumentException("ca-file is required: only the Legado CA is trusted");
            }
        } else {
            throw new IllegalArgumentException("base-url must use https");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout-ms must be positive");
        }
        if (keyId == null || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalArgumentException("key-id must match " + KEY_ID_PATTERN.pattern());
        }
        if (signingKey.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("signing-key must have at least 256 bits");
        }
        signingKey = signingKey.clone();
    }

    /** Lê a configuração do provider; falha rápido (sem ecoar a chave) se algo estiver ausente ou inválido. */
    public static LegacyClientSettings fromConfig(Config.Scope config) {
        String baseUrl = require(config, OPTION_BASE_URL);
        String caFile = config.get(OPTION_CA_FILE);
        return new LegacyClientSettings(URI.create(baseUrl.strip()),
                Duration.ofMillis(config.getInt(OPTION_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)),
                require(config, OPTION_KEY_ID).strip(), decodeKey(require(config, OPTION_SIGNING_KEY)),
                caFile == null || caFile.isBlank() ? null : Path.of(caFile.strip()),
                false);
    }

    private static String require(Config.Scope config, String option) {
        String value = config.get(option);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(option + " is required");
        }
        return value;
    }

    private static byte[] decodeKey(String base64) {
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            // A mensagem original poderia ecoar parte da chave.
            throw new IllegalArgumentException("signing-key must be Base64");
        }
    }

    @Override
    public byte[] signingKey() {
        return signingKey.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LegacyClientSettings that && baseUrl.equals(that.baseUrl)
                && timeout.equals(that.timeout) && Objects.equals(keyId, that.keyId)
                && Arrays.equals(signingKey, that.signingKey) && Objects.equals(caFile, that.caFile)
                && allowInsecureHttp == that.allowInsecureHttp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl, timeout, keyId, Arrays.hashCode(signingKey), caFile, allowInsecureHttp);
    }

    @Override
    public String toString() {
        return "LegacyClientSettings[baseUrl=" + baseUrl + ", timeout=" + timeout + ", keyId=" + keyId
                + ", caFile=" + caFile + ", allowInsecureHttp=" + allowInsecureHttp + "]";
    }
}
