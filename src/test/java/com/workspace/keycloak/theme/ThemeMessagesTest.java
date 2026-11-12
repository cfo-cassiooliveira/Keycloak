package com.workspace.keycloak.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** Garante que o tema de login tem os mesmos textos em todos os idiomas suportados (US-03). */
class ThemeMessagesTest {

    private static final String MESSAGES_PATH = "/theme/workspace/login/messages/";

    @Test
    void messages_PortugueseAndEnglishDefineTheSameKeys() throws IOException {
        Properties portuguese = load("messages_pt_BR.properties");
        Properties english = load("messages_en.properties");

        assertThat(portuguese).isNotEmpty();
        assertThat(portuguese.stringPropertyNames()).isEqualTo(english.stringPropertyNames());
    }

    @Test
    void themeProperties_ExtendBaseWithBootstrapAndDeclareSupportedLocales() throws IOException {
        Properties theme = new Properties();
        try (InputStream stream = resource("/theme/workspace/login/theme.properties")) {
            theme.load(stream);
        }

        assertThat(theme.getProperty("parent")).isEqualTo("base");
        assertThat(theme.getProperty("locales")).isEqualTo("pt-BR,en");
        assertThat(theme.getProperty("styles"))
                .isEqualTo("vendor/bootstrap/bootstrap.min.css css/workspace.css");
        // O Maven troca ${project.version} pela versão real (cache-busting dos recursos do tema).
        assertThat(theme.getProperty("workspaceAssetsVersion")).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
        assertThat(theme.getProperty("kcButtonPrimaryClass")).isEqualTo("btn-primary");
        assertThat(theme.getProperty("kcInputClass")).contains("form-control");
    }

    @Test
    void build_UnpacksBootstrapAndBundlesFontWithLicenses() throws IOException {
        String resources = "/theme/workspace/login/resources/";

        // Falha se o build deixar de extrair o WebJar do Bootstrap para o tema.
        try (InputStream bootstrap = resource(resources + "vendor/bootstrap/bootstrap.min.css");
                InputStream bootstrapLicense = resource(resources + "vendor/bootstrap/LICENSE");
                InputStream font = resource(resources + "fonts/manrope/manrope-latin-wght-normal.woff2");
                InputStream fontLicense = resource(resources + "fonts/manrope/OFL.txt")) {
            assertThat(new String(bootstrap.readNBytes(64), StandardCharsets.UTF_8)).contains("Bootstrap");
            assertThat(bootstrapLicense).isNotEmpty();
            assertThat(font).isNotEmpty();
            assertThat(fontLicense).isNotEmpty();
        }
    }

    private static Properties load(String fileName) throws IOException {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(resource(MESSAGES_PATH + fileName),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static InputStream resource(String path) {
        InputStream stream = ThemeMessagesTest.class.getResourceAsStream(path);
        assertThat(stream).as("resource %s", path).isNotNull();
        return stream;
    }
}
