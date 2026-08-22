package com.jobseekercopilot.applicationtracker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ProductionDatabaseVerifierTest {

    private static final DefaultApplicationArguments NO_ARGUMENTS =
            new DefaultApplicationArguments(new String[0]);

    @Test
    void rejectsNonPostgresAndUnverifiedTransport() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty("spring.datasource.url", "jdbc:h2:mem:unsafe"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("requires PostgreSQL");

        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty(
                                "spring.datasource.url",
                                "jdbc:postgresql://database.example/applications")
                        .withProperty(
                                "spring.datasource.hikari.data-source-properties.sslmode",
                                "require"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("sslmode=verify-full");
    }

    @Test
    void rejectsPrivilegedOrWeakDatabaseCredentialsWithoutEchoingThem() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty("spring.datasource.username", "postgres"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("dedicated least-privilege");

        String weakSecret = "do-not-echo";
        Throwable failure = catchThrowable(() -> verifier(validEnvironment()
                        .withProperty("spring.datasource.password", weakSecret))
                .run(NO_ARGUMENTS));
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
        assertThat(failure.getMessage()).doesNotContain(weakSecret);
    }

    @Test
    void rejectsMissingEncryptionAndBackupEvidence() {
        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty(
                                "application-tracker.database.encryption-at-rest-enabled",
                                "false"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("encryption-at-rest-enabled");

        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty(
                                "application-tracker.database.backup-key-reference",
                                ""))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("backup-key-reference");
    }

    @Test
    void rejectsSchemaMutationAndDebugPathsBeforeMigration() {
        Flyway flyway = mock(Flyway.class);
        MockEnvironment unsafe = validEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", "update");

        assertThatThrownBy(() -> verifier(unsafe).migrate(flyway))
                .hasMessageContaining("schema mutation");
        verify(flyway, never()).migrate();

        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty("spring.flyway.clean-disabled", "false"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("Flyway clean");
        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty("spring.h2.console.enabled", "true"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("H2 console");
        assertThatThrownBy(() -> verifier(validEnvironment()
                        .withProperty("spring.jpa.show-sql", "true"))
                .run(NO_ARGUMENTS))
                .hasMessageContaining("SQL logging");
    }

    @Test
    void acceptsSafeProductionConfigurationAndRunsMigration() {
        Flyway flyway = mock(Flyway.class);

        assertThatCode(() -> verifier(validEnvironment()).migrate(flyway))
                .doesNotThrowAnyException();
        verify(flyway).migrate();
    }

    @Test
    void isolatedTestsCanExplicitlyDisableTheProductionAttestation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "application-tracker.database.production-safety-check",
                        "false");

        assertThatCode(() -> verifier(environment).run(NO_ARGUMENTS))
                .doesNotThrowAnyException();
    }

    private MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty(
                        "spring.datasource.url",
                        "jdbc:postgresql://database.example/applications?sslmode=verify-full")
                .withProperty("spring.datasource.username", "application_tracker")
                .withProperty(
                        "spring.datasource.password",
                        "managed-database-password-32-bytes")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.clean-disabled", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.h2.console.enabled", "false")
                .withProperty("spring.jpa.show-sql", "false")
                .withProperty(
                        "application-tracker.database.encryption-at-rest-enabled",
                        "true")
                .withProperty(
                        "application-tracker.database.encryption-key-reference",
                        "kms://application-tracker/database")
                .withProperty(
                        "application-tracker.database.backup-encryption-enabled",
                        "true")
                .withProperty(
                        "application-tracker.database.backup-key-reference",
                        "kms://application-tracker/backups");
    }

    private ProductionDatabaseVerifier verifier(MockEnvironment environment) {
        return new ProductionDatabaseVerifier(environment);
    }
}
