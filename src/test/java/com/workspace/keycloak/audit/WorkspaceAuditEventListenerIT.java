package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.ws.rs.core.Response;

import org.apache.http.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;
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

import com.workspace.keycloak.support.OidcHttp;
import com.workspace.keycloak.support.WorkspaceServerConfig;

/** Valida o listener rodando dentro de um Keycloak real (US-02 da Spec 001). */
@KeycloakIntegrationTest(config = WorkspaceServerConfig.class)
class WorkspaceAuditEventListenerIT {

    private static final String CLIENT_ID = "audit-test";
    private static final String USERNAME = "audit.user";
    private static final String EMAIL = "audit.user@example.com";
    private static final String PASSWORD = "Audit-Test-Pass-1";
    private static final Duration LOG_TIMEOUT = Duration.ofSeconds(15);

    @InjectRealm(config = AuditRealmConfig.class)
    ManagedRealm realm;

    @InjectSysLogServer
    SysLogServer sysLogServer;

    @InjectHttpClient
    HttpClient httpClient;

    private final List<String> auditMessages = new CopyOnWriteArrayList<>();
    private final SysLogListener auditListener = log -> {
        if (log.getMessage() != null && log.getMessage().contains("\"audit_schema\":1")) {
            auditMessages.add(log.getMessage());
        }
    };

    @BeforeEach
    void listenToAuditLogs() {
        sysLogServer.addListener(auditListener);
    }

    @AfterEach
    void stopListening() {
        sysLogServer.removeListener(auditListener);
    }

    @Test
    void passwordGrant_WhenPasswordIsWrong_LogsLoginErrorWithoutPersonalData() throws Exception {
        OidcHttp.Response response = OidcHttp.passwordGrant(
                httpClient, realm.getBaseUrl(), CLIENT_ID, USERNAME, "wrong-password");

        // RFC 6749: credencial inválida no password grant retorna 400 invalid_grant.
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("invalid_grant");
        String auditMessage = awaitAuditMessage("\"type\":\"LOGIN_ERROR\"");
        assertThat(auditMessage)
                .contains("\"error\":\"invalid_user_credentials\"")
                .doesNotContain(USERNAME, EMAIL, "wrong-password", "ipAddress", "ip_address");
    }

    @Test
    void passwordGrant_WhenCredentialsAreValid_LogsLoginWithTechnicalUserId() throws Exception {
        OidcHttp.Response response = OidcHttp.passwordGrant(
                httpClient, realm.getBaseUrl(), CLIENT_ID, USERNAME, PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        String userId = realm.admin().users().search(USERNAME).getFirst().getId();
        String auditMessage = awaitAuditMessage("\"type\":\"LOGIN\"");
        assertThat(auditMessage)
                .contains("\"user_id\":\"" + userId + "\"")
                .doesNotContain(USERNAME, EMAIL, "session");
    }

    @Test
    void adminCreatesUser_LogsAdminEventWithoutRepresentation() {
        UserRepresentation user = UserBuilder.create("created.user").email("created.user@example.com").build();

        try (Response response = realm.admin().users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }

        String auditMessage = awaitAuditMessage("\"kind\":\"admin\"");
        assertThat(auditMessage)
                .contains("\"operation\":\"CREATE\"", "\"resource_type\":\"USER\"")
                .doesNotContain("created.user");
    }

    private String awaitAuditMessage(String marker) {
        Instant deadline = Instant.now().plus(LOG_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Optional<String> match = auditMessages.stream().filter(message -> message.contains(marker)).findFirst();
            if (match.isPresent()) {
                return match.get();
            }
            sleep();
        }
        throw new AssertionError("No audit log containing " + marker + " within " + LOG_TIMEOUT
                + ". Received: " + auditMessages);
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for audit log", e);
        }
    }

    /** Realm mínimo só com o listener do Workspace e sem gravação de eventos no banco (risco R7). */
    static class AuditRealmConfig implements RealmConfig {

        @Override
        public RealmBuilder configure(RealmBuilder realm) {
            return realm
                    .setEventsListeners(List.of(WorkspaceAuditEventListenerProviderFactory.PROVIDER_ID))
                    .eventsEnabled(false)
                    // Não grava ações administrativas no banco (guardariam o IP); o listener recebe assim mesmo (R2).
                    .adminEventsEnabled(false)
                    .adminEventsDetailsEnabled(false)
                    .clients(ClientBuilder.create(CLIENT_ID).publicClient().directAccessGrantsEnabled())
                    .users(UserBuilder.create(USERNAME)
                            .password(PASSWORD)
                            .email(EMAIL)
                            .name("Audit", "User")
                            .emailVerified(true)
                            .enabled(true));
        }
    }
}
