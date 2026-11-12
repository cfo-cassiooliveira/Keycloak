package com.workspace.keycloak.audit.gelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class GraylogSettingsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Path FALLBACK = Path.of("/opt/keycloak/data/log/audit-fallback.log");

    @ParameterizedTest
    @ValueSource(strings = {"http://graylog:12202/gelf", "https://graylog.example.com/gelf", "HTTP://graylog:12202/gelf"})
    void constructor_WhenUrlIsAbsoluteHttp_Accepts(String url) {
        GraylogSettings settings = new GraylogSettings(URI.create(url), TIMEOUT, 10, FALLBACK);

        assertThat(settings.endpoint()).isEqualTo(URI.create(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"graylog:12202/gelf", "/gelf", "ftp://graylog/gelf", "udp://graylog:12201"})
    void constructor_WhenUrlIsNotAbsoluteHttp_FailsFastWithTheBadValue(String url) {
        assertThatThrownBy(() -> new GraylogSettings(URI.create(url), TIMEOUT, 10, FALLBACK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graylog-url")
                .hasMessageContaining(url);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void constructor_WhenTimeoutIsNotPositive_FailsFast(long millis) {
        assertThatThrownBy(() -> new GraylogSettings(URI.create("http://graylog:12202/gelf"),
                Duration.ofMillis(millis), 10, FALLBACK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graylog-timeout-ms");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void constructor_WhenQueueCapacityIsNotPositive_FailsFast(int capacity) {
        assertThatThrownBy(() -> new GraylogSettings(URI.create("http://graylog:12202/gelf"), TIMEOUT, capacity,
                FALLBACK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graylog-queue-capacity");
    }

    @Test
    void constructor_WhenRequiredValueIsMissing_Fails() {
        assertThatThrownBy(() -> new GraylogSettings(null, TIMEOUT, 10, FALLBACK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraylogSettings(URI.create("http://graylog:12202/gelf"), null, 10, FALLBACK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraylogSettings(URI.create("http://graylog:12202/gelf"), TIMEOUT, 10, null))
                .isInstanceOf(NullPointerException.class);
    }
}
