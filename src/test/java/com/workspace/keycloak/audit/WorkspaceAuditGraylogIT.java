package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.InjectSysLogServer;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.events.SysLogListener;
import org.keycloak.testframework.events.SysLogServer;
import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.RealmBuilder;
import org.keycloak.testframework.realm.RealmConfig;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakServerConfig;
import org.keycloak.testframework.server.KeycloakServerConfigBuilder;

import com.workspace.keycloak.support.FakeGraylog;
import com.workspace.keycloak.support.OidcHttp;

/** Envio da auditoria ao Graylog (GELF HTTP) e fallback local num Keycloak real (ADR-0004). */
@KeycloakIntegrationTest(config = WorkspaceAuditGraylogIT.GraylogServerConfig.class)
class WorkspaceAuditGraylogIT {

    private static final String CLIENT_ID = "graylog-test";
    private static final String USERNAME = "graylog.user";
    private static final String EMAIL = "graylog.user@example.com";
    private static final String PASSWORD = "Graylog-Test-Pass-1";
    private static final Duration WAIT = Duration.ofSeconds(20);

    private final FakeGraylog graylog = FakeGraylog.instance();
    private final List<String> serverLogs = new CopyOnWriteArrayList<>();
    private final SysLogListener logListener = log -> serverLogs.add(String.valueOf(log.getMessage()));

    @InjectRealm(config = GraylogRealmConfig.class)
    ManagedRealm realm;

    @InjectSysLogServer
    SysLogServer sysLogServer;

    @InjectHttpClient
    HttpClient httpClient;

    @BeforeEach
    void resetFakeGraylog() throws Exception {
        graylog.reset();
        sysLogServer.addListener(logListener);
    }

    @AfterEach
    void stopListening() {
        sysLogServer.removeListener(logListener);
    }

    @Test
    void loginError_WhenGraylogAccepts_SendsRichGelfWithUserIdAndNoPersonalData() throws Exception {
        String userId = realm.admin().users().search(USERNAME).getFirst().getId();

        OidcHttp.passwordGrant(httpClient, realm.getBaseUrl(), CLIENT_ID, USERNAME, "wrong-password");

        String gelf = awaitGelf("\"_type\":\"LOGIN_ERROR\"");
        assertThat(gelf).contains(
                "\"version\":\"1.1\"",
                "\"level\":4",
                "\"_service_name\":\"keycloak\"",
                "\"_keycloak_version\":\"26.8.2\"",
                "\"_user_id\":\"" + userId + "\"",
                "\"_client_id\":\"" + CLIENT_ID + "\"",
                "\"_error\":\"invalid_user_credentials\"",
                "\"_event_id\":");
        assertThat(gelf).doesNotContain(USERNAME, EMAIL, "wrong-password", "ip_address", "ipAddress");
        assertThat(graylog.fallbackFile()).doesNotExist();
    }

    @Test
    void loginError_WhenGraylogIsDown_SavesToFallbackAndReportsItThenAlertsOnRecovery() throws Exception {
        graylog.reject();

        OidcHttp.passwordGrant(httpClient, realm.getBaseUrl(), CLIENT_ID, USERNAME, "wrong-password");

        await().atMost(WAIT).until(() -> Files.exists(graylog.fallbackFile())
                && Files.readString(graylog.fallbackFile()).contains("\"_type\":\"LOGIN_ERROR\""));
        assertThat(Files.readString(graylog.fallbackFile())).doesNotContain(USERNAME, EMAIL);
        await().atMost(WAIT).until(() -> serverLogs.stream().anyMatch(line ->
                line.contains("Could not save audit log to Graylog") && line.contains("fallback performed")));

        graylog.accept();
        OidcHttp.passwordGrant(httpClient, realm.getBaseUrl(), CLIENT_ID, USERNAME, PASSWORD);

        String alert = awaitGelf("\"_type\":\"AUDIT_FALLBACK_USED\"");
        assertThat(alert).contains("\"_fallback_count\":1", "\"_kind\":\"system\"");
    }

    private String awaitGelf(String marker) {
        await().atMost(WAIT).until(() -> graylog.received().stream().anyMatch(gelf -> gelf.contains(marker)));
        return graylog.received().stream().filter(gelf -> gelf.contains(marker)).findFirst().orElseThrow();
    }

    /** Aponta o listener para o Graylog falso, com o arquivo de fallback numa pasta temporária. */
    public static class GraylogServerConfig implements KeycloakServerConfig {

        @Override
        public KeycloakServerConfigBuilder configure(KeycloakServerConfigBuilder config) {
            FakeGraylog graylog = FakeGraylog.instance();
            return config.dependencyCurrentProject()
                    .spiOption("events-listener", "workspace-audit", "graylog-url", graylog.gelfUrl())
                    .spiOption("events-listener", "workspace-audit", "graylog-timeout-ms", "1000")
                    .spiOption("events-listener", "workspace-audit", "fallback-file",
                            graylog.fallbackFile().toString())
                    .spiOption("events-listener", "workspace-audit", "environment", "test");
        }
    }

    static class GraylogRealmConfig implements RealmConfig {

        @Override
        public RealmBuilder configure(RealmBuilder realm) {
            return realm
                    .setEventsListeners(List.of(WorkspaceAuditEventListenerProviderFactory.PROVIDER_ID))
                    .eventsEnabled(false)
                    .clients(ClientBuilder.create(CLIENT_ID).publicClient().directAccessGrantsEnabled())
                    .users(UserBuilder.create(USERNAME)
                            .password(PASSWORD)
                            .email(EMAIL)
                            .name("Graylog", "User")
                            .emailVerified(true)
                            .enabled(true));
        }
    }
}
