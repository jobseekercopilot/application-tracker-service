package com.jobseekercopilot.applicationtracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresApplicationRecoveryIntegrationTest {

    private static final String PRIMARY_DATABASE = "application_tracker";
    private static final String RESTORED_DATABASE = "application_tracker_restore";

    @org.testcontainers.junit.jupiter.Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15.18-alpine3.23")
                    .withDatabaseName(PRIMARY_DATABASE)
                    .withUsername("application_tracker")
                    .withPassword("application_tracker");

    @Test
    void legacyUpgradeRestartBackupRestoreRollbackAndDeletionAreProven()
            throws Exception {
        Flyway legacyFlyway = legacyFlyway(POSTGRES.getJdbcUrl());
        assertThat(legacyFlyway.migrate().migrationsExecuted).isEqualTo(1);

        UUID applicationId = UUID.randomUUID();
        try (Connection connection = primaryConnection()) {
            insertLegacyApplication(connection, applicationId);
            assertThatThrownBy(() -> insertInvalidStatus(connection))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState())
                    .isEqualTo("23514");
        }

        assertThatThrownBy(() -> DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), "wrong-password"))
                .isInstanceOf(SQLException.class)
                .satisfies(error ->
                        assertThat(((SQLException) error).getSQLState()).startsWith("28"));

        Flyway upgraded = flyway(POSTGRES.getJdbcUrl());
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(19);
        upgraded.validate();

        try (Connection connection = primaryConnection()) {
            assertUpgradedApplication(connection, applicationId);
            assertLegacyReaderStillWorks(connection, applicationId);
            assertFixtureIndexIsScoped(connection);
            assertLegacySnapshotEvent(connection, applicationId);
            assertLegacyReconciliationPending(connection, applicationId);
            assertSavedStatusConstraints(connection);
        }

        // Discard application-side Flyway/JDBC state and repeat the startup path
        // against the same PostgreSQL data.
        assertThat(flyway(POSTGRES.getJdbcUrl()).migrate().migrationsExecuted)
                .isZero();
        try (Connection afterRestart = primaryConnection()) {
            assertUpgradedApplication(afterRestart, applicationId);
            assertLegacySnapshotEvent(afterRestart, applicationId);
            assertLegacyReconciliationPending(
                    afterRestart, applicationId);
        }

        assertExecSucceeded(POSTGRES.execInContainer(
                "pg_dump",
                "--username=" + POSTGRES.getUsername(),
                "--format=custom",
                "--file=/tmp/application-tracker.dump",
                PRIMARY_DATABASE));
        assertExecSucceeded(POSTGRES.execInContainer(
                "createdb",
                "--username=" + POSTGRES.getUsername(),
                RESTORED_DATABASE));
        assertExecSucceeded(POSTGRES.execInContainer(
                "pg_restore",
                "--username=" + POSTGRES.getUsername(),
                "--dbname=" + RESTORED_DATABASE,
                "--no-owner",
                "/tmp/application-tracker.dump"));

        flyway(restoredJdbcUrl()).validate();
        try (Connection restored = restoredConnection()) {
            assertUpgradedApplication(restored, applicationId);
            assertLegacySnapshotEvent(restored, applicationId);
            assertLegacyReconciliationPending(restored, applicationId);
            assertEventHistoryIsAppendOnly(restored, applicationId);
            try (PreparedStatement delete = restored.prepareStatement(
                    "DELETE FROM application_records WHERE id = ?")) {
                delete.setObject(1, applicationId);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            assertThat(count(restored, applicationId)).isZero();
            assertThat(reconciliationCount(restored, applicationId))
                    .isZero();
            assertLegacySnapshotEvent(restored, applicationId);
        }
    }

    private Flyway flyway(String jdbcUrl) {
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/common")
                .cleanDisabled(true)
                .load();
    }

    private Flyway legacyFlyway(String jdbcUrl) {
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/common")
                .target("1")
                .cleanDisabled(true)
                .load();
    }

    private Connection primaryConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private Connection restoredConnection() throws SQLException {
        return DriverManager.getConnection(
                restoredJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private String restoredJdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                RESTORED_DATABASE);
    }

    private void insertLegacyApplication(Connection connection, UUID applicationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_records (
                    id, user_id, job_id, job_title, company_name, location,
                    cv_document_id, cover_letter_document_id, status,
                    created_at, updated_at, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            LocalDateTime now = LocalDateTime.of(2026, 7, 26, 18, 0);
            statement.setObject(1, applicationId);
            statement.setString(2, "synthetic-recovery-owner");
            statement.setString(3, "synthetic-legacy-job");
            statement.setString(4, "Synthetic Software Developer");
            statement.setString(5, "Example Employer");
            statement.setString(6, "Leeds");
            statement.setString(7, "synthetic-cv-document");
            statement.setString(8, "synthetic-cover-letter-document");
            statement.setString(9, "APPLIED");
            statement.setObject(10, now);
            statement.setObject(11, now);
            statement.setObject(12, now);
            statement.executeUpdate();
        }
    }

    private void insertInvalidStatus(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_records (
                    id, user_id, job_id, job_title, company_name,
                    cv_document_id, cover_letter_document_id, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "synthetic-owner");
            statement.setString(3, "synthetic-invalid-job");
            statement.setString(4, "Invalid status row");
            statement.setString(5, "Example Employer");
            statement.setString(6, "synthetic-cv");
            statement.setString(7, "synthetic-cover-letter");
            statement.setString(8, "NOT_A_REAL_STATUS");
            statement.setObject(9, now);
            statement.setObject(10, now);
            statement.executeUpdate();
        }
    }

    private void assertUpgradedApplication(Connection connection, UUID applicationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT job_id, canonical_job_id, provider, external_job_id,
                       fixture_scenario_id,
                       cv_document_family_id,
                       cv_document_source_type,
                       cv_document_selected_at,
                       cover_letter_document_selected_at,
                       application_used_cv_document_id,
                       application_used_cover_letter_document_id,
                       record_version,
                       provenance,
                       idempotency_key,
                       create_request_fingerprint
                FROM application_records
                WHERE id = ?
                """)) {
            statement.setObject(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("job_id"))
                        .isEqualTo("synthetic-legacy-job");
                assertThat(result.getString("canonical_job_id"))
                        .isEqualTo("synthetic-legacy-job");
                assertThat(result.getString("provider")).isEqualTo("LEGACY");
                assertThat(result.getString("external_job_id"))
                        .isEqualTo("synthetic-legacy-job");
                assertThat(result.getString("fixture_scenario_id")).isNull();
                assertThat(result.getString("cv_document_family_id")).isNull();
                assertThat(result.getString("cv_document_source_type")).isNull();
                assertThat(result.getObject("cv_document_selected_at"))
                        .isNotNull();
                assertThat(result.getObject("cover_letter_document_selected_at"))
                        .isNotNull();
                assertThat(result.getString("application_used_cv_document_id")).isNull();
                assertThat(result.getString("application_used_cover_letter_document_id")).isNull();
                assertThat(result.getLong("record_version")).isZero();
                assertThat(result.getString("provenance")).isEqualTo("GENERATED");
                assertThat(result.getString("idempotency_key")).isNull();
                assertThat(result.getString("create_request_fingerprint")).isNull();
            }
        }
    }

    private void assertLegacyReaderStillWorks(
            Connection connection, UUID applicationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT user_id, job_id, job_title, status, updated_at
                FROM application_records
                WHERE id = ?
                """)) {
            statement.setObject(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("APPLIED");
            }
        }
    }

    private void assertFixtureIndexIsScoped(Connection connection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT indexdef
                FROM pg_indexes
                WHERE tablename = 'application_records'
                  AND indexname = 'idx_application_records_owner_fixture_scenario'
                """);
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("indexdef"))
                    .contains("fixture_scenario_id IS NOT NULL");
        }
    }

    private void assertLegacySnapshotEvent(
            Connection connection, UUID applicationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_type, from_status, to_status, actor_type,
                       actor_id, source, occurred_at, recorded_at,
                       reason, record_version
                FROM application_events
                WHERE application_id = ?
                """)) {
            statement.setObject(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("event_type"))
                        .isEqualTo("LEGACY_SNAPSHOT");
                assertThat(result.getString("from_status")).isNull();
                assertThat(result.getString("to_status")).isEqualTo("APPLIED");
                assertThat(result.getString("actor_type")).isEqualTo("SYSTEM");
                assertThat(result.getString("actor_id")).isEqualTo("migration-v7");
                assertThat(result.getString("source")).isEqualTo("MIGRATION");
                assertThat(result.getObject("occurred_at")).isNotNull();
                assertThat(result.getObject("recorded_at")).isNotNull();
                assertThat(result.getString("reason"))
                        .contains("history before event tracking is unavailable");
                assertThat(result.getLong("record_version")).isZero();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void assertLegacyReconciliationPending(
            Connection connection, UUID applicationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT user_id, status, issue_codes, checked_at,
                       attempt_count, repair_count,
                       application_record_version
                FROM application_document_reconciliations
                WHERE application_id = ?
                """)) {
            statement.setObject(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("user_id"))
                        .isEqualTo("synthetic-recovery-owner");
                assertThat(result.getString("status"))
                        .isEqualTo("PENDING");
                assertThat(result.getString("issue_codes")).isNull();
                assertThat(result.getObject("checked_at")).isNull();
                assertThat(result.getInt("attempt_count")).isZero();
                assertThat(result.getInt("repair_count")).isZero();
                assertThat(result.getLong("application_record_version"))
                        .isZero();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void assertSavedStatusConstraints(Connection connection)
            throws SQLException {
        UUID savedId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_records (
                    id, user_id, job_id, canonical_job_id, provider,
                    external_job_id, provenance, job_title, company_name,
                    status, created_at, updated_at, record_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SAVED', ?, ?, 0)
                """)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(1, savedId);
            statement.setString(2, "saved-owner");
            statement.setString(3, "saved-job");
            statement.setString(4, "saved-job");
            statement.setString(5, "MANUAL");
            statement.setString(6, "saved-job");
            statement.setString(7, "MANUAL");
            statement.setString(8, "Saved developer role");
            statement.setString(9, "Example Employer");
            statement.setObject(10, now);
            statement.setObject(11, now);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement event = connection.prepareStatement("""
                INSERT INTO application_events (
                    id, application_id, user_id, event_type, from_status,
                    to_status, actor_type, actor_id, source, occurred_at,
                    recorded_at, record_version
                ) VALUES (
                    ?, ?, 'saved-owner', 'APPLICATION_SAVED', NULL,
                    'SAVED', 'USER', 'saved-owner', 'USER',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
                )
                """)) {
            event.setObject(1, UUID.randomUUID());
            event.setObject(2, savedId);
            assertThat(event.executeUpdate()).isEqualTo(1);
        }

        UUID explicitlyOmittedId =
                insertManualDocumentsGenerated(connection, false);
        UUID preparedId =
                insertManualDocumentsGenerated(connection, true);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM application_records WHERE id IN (?, ?)")) {
            delete.setObject(1, explicitlyOmittedId);
            delete.setObject(2, preparedId);
            assertThat(delete.executeUpdate()).isEqualTo(2);
        }
    }

    private UUID insertManualDocumentsGenerated(
            Connection connection, boolean completePair) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_records (
                    id, user_id, job_id, canonical_job_id, provider,
                    external_job_id, provenance, job_title, company_name,
                    cv_document_id, cover_letter_document_id, status,
                    created_at, updated_at, record_version
                ) VALUES (
                    ?, 'prepared-owner', ?, ?, 'REED', ?, 'MANUAL',
                    'Prepared developer role', 'Example Employer', ?, ?,
                    'DOCUMENTS_GENERATED', ?, ?, 0
                )
                """)) {
            String jobId = "prepared-" + id;
            LocalDateTime now = LocalDateTime.now();
            statement.setObject(1, id);
            statement.setString(2, jobId);
            statement.setString(3, jobId);
            statement.setString(4, jobId);
            statement.setString(
                    5, completePair ? UUID.randomUUID().toString() : null);
            statement.setString(
                    6, completePair ? UUID.randomUUID().toString() : null);
            statement.setObject(7, now);
            statement.setObject(8, now);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            return id;
        }
    }

    private void assertEventHistoryIsAppendOnly(
            Connection connection, UUID applicationId) {
        assertThatThrownBy(() -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE application_events
                    SET reason = 'rewritten'
                    WHERE application_id = ?
                    """)) {
                update.setObject(1, applicationId);
                update.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        rollback(connection);

        assertThatThrownBy(() -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM application_events
                    WHERE application_id = ?
                    """)) {
                delete.setObject(1, applicationId);
                delete.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        rollback(connection);
    }

    private void rollback(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The next assertion will surface an unusable connection.
        }
    }

    private long count(Connection connection, UUID applicationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM application_records WHERE id = ?")) {
            statement.setObject(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private long reconciliationCount(
            Connection connection, UUID applicationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM application_document_reconciliations WHERE application_id = ?")) {
            statement.setObject(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private void assertExecSucceeded(Container.ExecResult result) {
        assertThat(result.getExitCode())
                .describedAs(result.getStderr())
                .isZero();
    }
}
