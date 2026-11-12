package com.workspace.keycloak.audit.gelf;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.util.JsonSerialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.workspace.keycloak.audit.AuditLevel;
import com.workspace.keycloak.audit.AuditRecord;

class GelfMessageFactoryTest {

    private static final ServiceInfo SERVICE = new ServiceInfo("keycloak", "0.3.0", "26.8.2", "local", "kc-1");

    private final GelfMessageFactory factory = new GelfMessageFactory(SERVICE);

    @Test
    void toGelf_WhenLoginFails_BuildsGelf11WithWarningLevelAndPrefixedFields() throws Exception {
        Map<String, Object> gelf = parse(factory.toGelf(userRecord(AuditLevel.WARN, "invalid_user_credentials")));

        assertThat(gelf)
                .containsEntry("version", "1.1")
                .containsEntry("host", "kc-1")
                .containsEntry("level", 4)
                .containsEntry("short_message", "Keycloak user event LOGIN_ERROR failed: invalid_user_credentials")
                .containsEntry("_service_name", "keycloak")
                .containsEntry("_service_version", "0.3.0")
                .containsEntry("_keycloak_version", "26.8.2")
                .containsEntry("_environment", "local")
                .containsEntry("_log_source", "audit")
                .containsEntry("_event_id", "event-1")
                .containsEntry("_user_id", "user-1")
                .containsEntry("_error", "invalid_user_credentials");
        assertThat(new BigDecimal(gelf.get("timestamp").toString())).isEqualByComparingTo("1763000000.123");
    }

    @Test
    void toGelf_WhenEventSucceeds_UsesInformationalLevelAndNoErrorSuffix() throws Exception {
        Map<String, Object> gelf = parse(factory.toGelf(userRecord(AuditLevel.INFO, null)));

        assertThat(gelf)
                .containsEntry("level", 6)
                .containsEntry("short_message", "Keycloak user event LOGIN_ERROR")
                .doesNotContainKey("_error");
    }

    @Test
    void toGelf_WhenAdminEvent_DescribesOperationAndResource() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "admin");
        fields.put("operation", "DELETE");
        fields.put("resource_type", "USER");
        fields.put("target_user_id", "3f0c5d0e-8a4b-4c1e-9f2a-6b7d8e9f0a1b");

        Map<String, Object> gelf = parse(factory.toGelf(new AuditRecord(AuditLevel.INFO, fields)));

        assertThat(gelf)
                .containsEntry("short_message", "Keycloak admin event DELETE USER")
                .containsEntry("_target_user_id", "3f0c5d0e-8a4b-4c1e-9f2a-6b7d8e9f0a1b");
    }

    @Test
    void toGelf_NeverUsesTheReservedIdFieldAndKeepsOnlyRecordFields() throws Exception {
        Map<String, Object> gelf = parse(factory.toGelf(userRecord(AuditLevel.INFO, null)));

        assertThat(gelf).doesNotContainKey("_id");
        assertThat(gelf.keySet()).allMatch(key -> key.startsWith("_")
                || Map.of("version", 1, "host", 1, "short_message", 1, "timestamp", 1, "level", 1).containsKey(key));
    }

    @Test
    void toGelf_WhenRecordHasNoTimestamp_UsesCurrentTime() throws Exception {
        Map<String, Object> gelf = parse(factory.toGelf(new AuditRecord(AuditLevel.INFO, Map.of("type", "LOGIN"))));

        assertThat(new BigDecimal(gelf.get("timestamp").toString())).isPositive();
    }

    private static AuditRecord userRecord(AuditLevel level, String error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("audit_schema", 1);
        fields.put("kind", "user");
        fields.put("event_id", "event-1");
        fields.put("type", "LOGIN_ERROR");
        fields.put("user_id", "user-1");
        if (error != null) {
            fields.put("error", error);
        }
        fields.put("occurred_at", "2025-11-13T02:13:20.123Z");
        return new AuditRecord(level, fields);
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return JsonSerialization.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }
}
