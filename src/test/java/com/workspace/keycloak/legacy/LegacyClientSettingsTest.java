package com.workspace.keycloak.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.Config;

class LegacyClientSettingsTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(KEY);
    private static final Path CA = Path.of("/opt/keycloak/conf/legado-ca.pem");
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static LegacyClientSettings settings(String url, byte[] key, Path ca, boolean insecure) {
        return new LegacyClientSettings(URI.create(url), TIMEOUT, "keycloak-1", key, ca, insecure);
    }

    @Test
    void constructor_WhenHttpsWithCa_Accepts() {
        assertThat(settings("https://legado-api:8443", KEY, CA, false).baseUrl().getHost()).isEqualTo("legado-api");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://legado-api:8080", "HTTP://legado-api:8080"})
    void constructor_WhenHttpWithoutTestFlag_IsRefused(String url) {
        assertThatThrownBy(() -> settings(url, KEY, CA, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void constructor_WhenHttpWithExplicitTestFlag_IsAcceptedWithoutCa() {
        assertThat(settings("http://localhost:1", KEY, null, true).allowInsecureHttp()).isTrue();
    }

    @Test
    void constructor_WhenHttpsWithoutCa_IsRefused() {
        assertThatThrownBy(() -> settings("https://legado-api:8443", KEY, null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ca-file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://legado-api", "legado-api:8443", "/relative", "https://legado-api:8443/path",
            "https://legado-api:8443?x=1", "https://user@legado-api:8443"})
    void constructor_WhenUrlIsNotOriginOnly_IsRefused(String url) {
        assertThatThrownBy(() -> settings(url, KEY, CA, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_WhenKeyIsShortOrKeyIdIsInvalidOrTimeoutNotPositive_IsRefused() {
        URI url = URI.create("https://legado-api:8443");
        assertThatThrownBy(() -> new LegacyClientSettings(url, TIMEOUT, "keycloak-1", new byte[8], CA, false))
                .hasMessageContaining("256 bits");
        assertThatThrownBy(() -> new LegacyClientSettings(url, TIMEOUT, "bad id, t=1", KEY, CA, false))
                .hasMessageContaining("key-id");
        assertThatThrownBy(() -> new LegacyClientSettings(url, Duration.ZERO, "keycloak-1", KEY, CA, false))
                .hasMessageContaining("timeout");
    }

    @Test
    void toString_NeverExposesTheKey() {
        LegacyClientSettings settings = settings("https://legado-api:8443", KEY, CA, false);

        assertThat(settings.toString()).doesNotContain(KEY_BASE64).doesNotContain(new String(KEY,
                java.nio.charset.StandardCharsets.UTF_8)).contains("keycloak-1");
    }

    @Test
    void equalsAndHashCode_CompareKeyContent() {
        LegacyClientSettings a = settings("https://legado-api:8443", KEY, CA, false);
        LegacyClientSettings b = settings("https://legado-api:8443", KEY.clone(), CA, false);
        LegacyClientSettings other = settings("https://legado-api:8443", new byte[32], CA, false);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(other);
    }

    @Test
    void signingKey_ReturnsACopy() {
        LegacyClientSettings settings = settings("https://legado-api:8443", KEY, CA, false);

        settings.signingKey()[0] = 0;

        assertThat(settings.signingKey()).isEqualTo(KEY);
    }

    @Test
    void fromConfig_ReadsOptionsAndDecodesTheBase64Key() {
        Config.Scope config = scope("https://legado-api:8443", "keycloak-1", KEY_BASE64, "/opt/keycloak/conf/legado-ca.pem");
        when(config.getInt("timeout-ms", 2000)).thenReturn(1500);

        LegacyClientSettings settings = LegacyClientSettings.fromConfig(config);

        assertThat(settings.baseUrl()).isEqualTo(URI.create("https://legado-api:8443"));
        assertThat(settings.timeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(settings.keyId()).isEqualTo("keycloak-1");
        assertThat(settings.signingKey()).isEqualTo(KEY);
        assertThat(settings.caFile()).isEqualTo(Path.of("/opt/keycloak/conf/legado-ca.pem"));
        assertThat(settings.allowInsecureHttp()).isFalse();
    }

    @Test
    void fromConfig_WhenRequiredOptionIsMissing_FailsFastNamingTheOption() {
        Config.Scope config = scope("https://legado-api:8443", "keycloak-1", null, "/ca.pem");

        assertThatThrownBy(() -> LegacyClientSettings.fromConfig(config)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("signing-key is required");
    }

    @Test
    void fromConfig_WhenKeyIsNotBase64_FailsWithoutEchoingTheValue() {
        Config.Scope config = scope("https://legado-api:8443", "keycloak-1", "not-base64-secret-value!", "/ca.pem");

        assertThatThrownBy(() -> LegacyClientSettings.fromConfig(config)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("signing-key must be Base64");
    }

    @Test
    void fromConfig_WhenUrlIsHttp_IsRefused() {
        Config.Scope config = scope("http://legado-api:8080", "keycloak-1", KEY_BASE64, "/ca.pem");

        assertThatThrownBy(() -> LegacyClientSettings.fromConfig(config)).hasMessageContaining("https");
    }

    // A opção allow-insecure-http não existe mais na configuração: mesmo "true" no ambiente não libera http.
    @Test
    void fromConfig_WhenInsecureFlagIsSetInConfig_IsStillRefused() {
        Config.Scope config = scope("http://legado-api:8080", "keycloak-1", KEY_BASE64, null);
        when(config.getBoolean("allow-insecure-http", false)).thenReturn(true);

        assertThatThrownBy(() -> LegacyClientSettings.fromConfig(config)).hasMessageContaining("https");
    }

    private static Config.Scope scope(String url, String keyId, String key, String ca) {
        Config.Scope config = mock(Config.Scope.class);
        when(config.get("base-url")).thenReturn(url);
        when(config.get("key-id")).thenReturn(keyId);
        when(config.get("signing-key")).thenReturn(key);
        when(config.get("ca-file")).thenReturn(ca);
        when(config.getInt("timeout-ms", 2000)).thenReturn(2000);
        return config;
    }
}
