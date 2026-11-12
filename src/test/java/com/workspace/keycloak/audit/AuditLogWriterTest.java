package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

class AuditLogWriterTest {

    private final Logger logger = mock(Logger.class);
    private final AuditLogWriter writer = new AuditLogWriter(logger);

    @Test
    void write_WhenLevelIsInfo_LogsSingleLineJsonAtInfo() {
        writer.write(new AuditRecord(AuditLevel.INFO, fields("LOGIN")));

        verify(logger).info("{\"audit_schema\":1,\"type\":\"LOGIN\"}");
        verify(logger, never()).warn(any());
    }

    @Test
    void write_WhenLevelIsWarn_LogsJsonAtWarn() {
        writer.write(new AuditRecord(AuditLevel.WARN, fields("LOGIN_ERROR")));

        verify(logger).warn("{\"audit_schema\":1,\"type\":\"LOGIN_ERROR\"}");
        verify(logger, never()).info(any());
    }

    @Test
    void writeFailure_LogsErrorWithExceptionAndFixedMessage() {
        IllegalStateException failure = new IllegalStateException("boom");

        writer.writeFailure(failure);

        verify(logger).error("Failed to write audit record", failure);
    }

    @Test
    void write_WhenValueCannotBeSerialized_ThrowsUncheckedAndLogsNothing() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("invalid", new UnserializableValue());

        assertThatThrownBy(() -> writer.write(new AuditRecord(AuditLevel.INFO, fields)))
                .isInstanceOf(UncheckedIOException.class);
        verify(logger, never()).info(anyString());
    }

    // O Jackson converte a exceção do getter em JsonMappingException (IOException).
    static final class UnserializableValue {
        public String getValue() {
            throw new IllegalStateException("not serializable");
        }
    }

    private static Map<String, Object> fields(String type) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("audit_schema", 1);
        fields.put("type", type);
        return fields;
    }
}
