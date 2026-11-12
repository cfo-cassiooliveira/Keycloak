package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

import com.workspace.keycloak.audit.gelf.GraylogSettings;
import com.workspace.keycloak.audit.gelf.ServiceInfo;

class WorkspaceAuditEventListenerProviderFactoryTest {

    @Test
    void factory_CreatesSharedProviderWithWorkspaceAuditId() {
        WorkspaceAuditEventListenerProviderFactory factory = new WorkspaceAuditEventListenerProviderFactory();
        KeycloakSession session = mock(KeycloakSession.class);

        factory.init(scope(null));
        factory.postInit(null);

        assertThat(factory.getId()).isEqualTo("workspace-audit");
        assertThat(factory.create(session)).isNotNull().isSameAs(factory.create(session));
        assertThatCode(factory::close).doesNotThrowAnyException();
    }

    @Test
    void graylogSettings_WhenUrlIsMissingOrBlank_KeepsAuditOnTheConsole() {
        assertThat(WorkspaceAuditEventListenerProviderFactory.graylogSettings(null)).isEmpty();
        assertThat(WorkspaceAuditEventListenerProviderFactory.graylogSettings(scope(null))).isEmpty();
        assertThat(WorkspaceAuditEventListenerProviderFactory.graylogSettings(scope("  "))).isEmpty();
    }

    @Test
    void graylogSettings_WhenUrlIsSet_UsesDefaultsForOtherOptions() {
        GraylogSettings settings = WorkspaceAuditEventListenerProviderFactory
                .graylogSettings(scope("http://graylog:12202/gelf")).orElseThrow();

        assertThat(settings.endpoint()).isEqualTo(URI.create("http://graylog:12202/gelf"));
        assertThat(settings.timeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(settings.queueCapacity()).isEqualTo(10_000);
        assertThat(settings.fallbackFile()).isEqualTo(Path.of("/opt/keycloak/data/log/audit-fallback.log"));
    }

    @Test
    void factory_WhenGraylogIsConfigured_InitializesAndClosesTheGraylogSink() {
        WorkspaceAuditEventListenerProviderFactory factory = new WorkspaceAuditEventListenerProviderFactory();

        factory.init(scope("http://127.0.0.1:9/gelf"));

        assertThat(factory.create(mock(KeycloakSession.class))).isNotNull();
        assertThatCode(factory::close).doesNotThrowAnyException();
    }

    @Test
    void serviceInfo_IdentifiesKeycloakServiceVersionsEnvironmentAndHost() {
        ServiceInfo serviceInfo = WorkspaceAuditEventListenerProviderFactory.serviceInfo(scope(null));

        assertThat(serviceInfo.serviceName()).isEqualTo("keycloak");
        assertThat(serviceInfo.serviceVersion()).isNotBlank();
        assertThat(serviceInfo.keycloakVersion()).isNotBlank();
        assertThat(serviceInfo.environment()).isEqualTo("local");
        assertThat(serviceInfo.host()).isNotBlank();
    }

    @Test
    void configMetadata_DocumentsEveryOption() {
        assertThat(new WorkspaceAuditEventListenerProviderFactory().getConfigMetadata())
                .extracting(ProviderConfigProperty::getName)
                .containsExactly("graylog-url", "graylog-timeout-ms", "graylog-queue-capacity", "fallback-file",
                        "environment");
    }

    // Simula as opções do SPI: devolve o padrão de cada opção, exceto a URL informada.
    private static Config.Scope scope(String graylogUrl) {
        Config.Scope scope = mock(Config.Scope.class);
        when(scope.get("graylog-url")).thenReturn(graylogUrl);
        when(scope.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(scope.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(scope.get(eq("environment"), anyString())).thenReturn("local");
        return scope;
    }
}
