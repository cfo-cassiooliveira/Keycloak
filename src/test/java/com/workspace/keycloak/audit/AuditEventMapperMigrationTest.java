package com.workspace.keycloak.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.util.JsonSerialization;

class AuditEventMapperMigrationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final String USER_ID = "3f0c5d0e-8a4b-4c1e-9f2a-6b7d8e9f0a1b";
    private static final String LEGACY_ID = "0195f1d2-7c3a-7b11-9a00-000000000001";
    private static final String FULL_EMAIL = "ana.souza@gmail.com";
    private static final Set<MigrationAuditEvent> ADR_0007_TYPES = EnumSet.of(
            MigrationAuditEvent.LEGACY_USER_NOT_FOUND, MigrationAuditEvent.LEGACY_PASSWORD_MISMATCH,
            MigrationAuditEvent.LEGACY_ACCOUNT_LOCKED, MigrationAuditEvent.LEGACY_ALREADY_MIGRATED,
            MigrationAuditEvent.RESET_UNKNOWN_USER, MigrationAuditEvent.LEGACY_UNAVAILABLE);

    private final AuditEventMapper mapper = new AuditEventMapper();

    @ParameterizedTest
    @EnumSource(MigrationAuditEvent.class)
    void map_MaskedLoginIsKeptOnlyForTheSixTypesOfAdr0007(MigrationAuditEvent type) {
        AuditRecord auditRecord = mapper.map(MigrationAuditEntry.of(type).withMaskedLogin("ana***@gmail"), NOW);

        if (ADR_0007_TYPES.contains(type)) {
            assertThat(auditRecord.fields()).containsEntry("masked_login", "ana***@gmail");
        } else {
            assertThat(auditRecord.fields()).doesNotContainKey("masked_login");
        }
    }

    @Test
    void allowsMaskedLogin_MatchesExactlyTheAdr0007Types() {
        for (MigrationAuditEvent type : MigrationAuditEvent.values()) {
            assertThat(type.allowsMaskedLogin()).isEqualTo(ADR_0007_TYPES.contains(type));
        }
    }

    @Test
    void level_FollowsThePlanTable() {
        assertThat(MigrationAuditEvent.LEGACY_USER_MIGRATED.level()).isEqualTo(AuditLevel.INFO);
        assertThat(MigrationAuditEvent.LEGACY_USER_PREPARED_FOR_RESET.level()).isEqualTo(AuditLevel.INFO);
        assertThat(MigrationAuditEvent.LEGACY_MIGRATION_MARKED.level()).isEqualTo(AuditLevel.INFO);
        assertThat(MigrationAuditEvent.LEGACY_MIGRATION_MARK_FAILED.level()).isEqualTo(AuditLevel.ERROR);
        assertThat(MigrationAuditEvent.LEGACY_UNAVAILABLE.level()).isEqualTo(AuditLevel.ERROR);
        assertThat(MigrationAuditEvent.LEGACY_USER_NOT_FOUND.level()).isEqualTo(AuditLevel.WARN);
        assertThat(MigrationAuditEvent.RESET_UNKNOWN_USER.level()).isEqualTo(AuditLevel.WARN);
    }

    @Test
    void map_WhenMigrated_CarriesTechnicalIdsAndPasswordFlag() {
        AuditRecord auditRecord = mapper.map(MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_USER_MIGRATED)
                .withUserId(USER_ID).withLegacyUserId(LEGACY_ID).withPasswordUpdateRequired(true), NOW);

        assertThat(auditRecord.level()).isEqualTo(AuditLevel.INFO);
        assertThat(auditRecord.fields())
                .containsEntry("audit_schema", 1)
                .containsEntry("kind", "migration")
                .containsEntry("type", "LEGACY_USER_MIGRATED")
                .containsEntry("user_id", USER_ID)
                .containsEntry("legacy_user_id", LEGACY_ID)
                .containsEntry("password_update_required", true)
                .containsEntry("occurred_at", "2026-11-17T12:00:00Z")
                .containsKey("event_id")
                .doesNotContainKeys("masked_login", "flow", "error");
    }

    @Test
    void map_WhenUnavailable_CarriesErrorAndFlowAsErrorLevel() {
        AuditRecord auditRecord = mapper.map(MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_UNAVAILABLE)
                .withMaskedLogin("ana***@gmail").withFlow("login").withError("http_5xx"), NOW);

        assertThat(auditRecord.level()).isEqualTo(AuditLevel.ERROR);
        assertThat(auditRecord.fields()).containsEntry("flow", "login").containsEntry("error", "http_5xx");
    }

    @ParameterizedTest
    @ValueSource(strings = {FULL_EMAIL, "bruno.lima", "ana", "ana***@gmail.com", "ana*** ", "an***@", "ana@***", ""})
    void map_WhenMaskedLoginIsNotInMaskFormat_DropsIt(String value) {
        AuditRecord auditRecord = mapper.map(
                MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_USER_NOT_FOUND).withMaskedLogin(value), NOW);

        assertThat(auditRecord.fields()).doesNotContainKey("masked_login");
    }

    @ParameterizedTest
    @ValueSource(strings = {"***", "a***", "ana***", "ana***@gmail", "a***@x"})
    void map_WhenMaskedLoginIsInMaskFormat_KeepsIt(String value) {
        AuditRecord auditRecord = mapper.map(
                MigrationAuditEntry.of(MigrationAuditEvent.RESET_UNKNOWN_USER).withMaskedLogin(value), NOW);

        assertThat(auditRecord.fields()).containsEntry("masked_login", value);
    }

    @Test
    void map_KeepsTheTechnicalClientIdAndDropsAnythingElse() {
        MigrationAuditEntry entry = MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_USER_MIGRATED);

        assertThat(mapper.map(entry.withClientId("cadastro"), NOW).fields()).containsEntry("client_id", "cadastro");
        assertThat(mapper.map(entry.withClientId(FULL_EMAIL + " x"), NOW).fields()).doesNotContainKey("client_id");
        assertThat(mapper.map(entry, NOW).fields()).doesNotContainKey("client_id");
    }

    @Test
    void map_WhenFreeTextIsPassedInRestrictedFields_DropsIt() {
        AuditRecord auditRecord = mapper.map(MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_UNAVAILABLE)
                .withUserId(FULL_EMAIL).withLegacyUserId("ana.souza").withFlow(FULL_EMAIL).withError(FULL_EMAIL), NOW);

        assertThat(auditRecord.fields()).doesNotContainKeys("user_id", "legacy_user_id", "flow", "error");
    }

    @Test
    void map_NeverLetsAFullEmailOrLoginReachAnyField() throws Exception {
        for (MigrationAuditEvent type : MigrationAuditEvent.values()) {
            AuditRecord auditRecord = mapper.map(MigrationAuditEntry.of(type).withUserId(USER_ID)
                    .withLegacyUserId(LEGACY_ID).withMaskedLogin(FULL_EMAIL).withFlow("login").withError(FULL_EMAIL),
                    NOW);

            assertThat(JsonSerialization.writeValueAsString(auditRecord.fields()))
                    .doesNotContain(FULL_EMAIL).doesNotContain("ana.souza");
        }
    }

    @Test
    void map_WithoutExplicitTime_UsesCurrentUtcTime() {
        AuditRecord auditRecord = mapper.map(MigrationAuditEntry.of(MigrationAuditEvent.LEGACY_MIGRATION_MARKED));

        assertThat(Instant.parse(auditRecord.fields().get("occurred_at").toString())).isBeforeOrEqualTo(Instant.now());
    }
}
