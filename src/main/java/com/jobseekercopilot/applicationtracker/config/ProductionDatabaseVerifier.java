package com.jobseekercopilot.applicationtracker.config;

import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProductionDatabaseVerifier
        implements ApplicationRunner, FlywayMigrationStrategy {

    private static final Pattern VERIFIED_TLS_QUERY_PARAMETER =
            Pattern.compile(
                    "(?:[?&])sslmode=verify-full(?:&|$)",
                    Pattern.CASE_INSENSITIVE);
    private static final int MINIMUM_PASSWORD_LENGTH = 32;

    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        verifyConfiguration();
    }

    @Override
    public void migrate(Flyway flyway) {
        verifyConfiguration();
        flyway.migrate();
    }

    private void verifyConfiguration() {
        if (!environment.getProperty(
                "application-tracker.database.production-safety-check",
                Boolean.class,
                true)) {
            return;
        }

        String url = required("spring.datasource.url");
        if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                    "Application Tracker requires PostgreSQL outside isolated tests");
        }

        String configuredSslMode = environment.getProperty(
                "spring.datasource.hikari.data-source-properties.sslmode", "");
        if (!VERIFIED_TLS_QUERY_PARAMETER.matcher(url).find()
                && !"verify-full".equalsIgnoreCase(configuredSslMode)) {
            throw new IllegalStateException(
                    "Application Tracker database connections must use sslmode=verify-full");
        }

        String username = required("spring.datasource.username");
        if ("postgres".equalsIgnoreCase(username) || "root".equalsIgnoreCase(username)) {
            throw new IllegalStateException(
                    "Application Tracker requires a dedicated least-privilege database role");
        }

        String password = required("spring.datasource.password");
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Application Tracker database credentials must contain at least 32 characters");
        }

        requireTrue(
                "application-tracker.database.encryption-at-rest-enabled",
                "Managed database encryption at rest must be declared");
        required("application-tracker.database.encryption-key-reference");
        requireTrue(
                "application-tracker.database.backup-encryption-enabled",
                "Encrypted database backups must be declared");
        required("application-tracker.database.backup-key-reference");

        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "Reviewed Flyway migrations are required for Application Tracker");
        }
        if (!environment.getProperty(
                "spring.flyway.clean-disabled", Boolean.class, false)) {
            throw new IllegalStateException("Flyway clean must remain disabled");
        }
        if (!"validate".equalsIgnoreCase(required("spring.jpa.hibernate.ddl-auto"))) {
            throw new IllegalStateException(
                    "Hibernate schema mutation is forbidden; use reviewed Flyway migrations");
        }
        if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "The H2 console is forbidden outside isolated tests");
        }
        if (environment.getProperty("spring.jpa.show-sql", Boolean.class, false)) {
            throw new IllegalStateException(
                    "SQL logging is forbidden outside isolated tests");
        }
    }

    private void requireTrue(String property, String message) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            throw new IllegalStateException(message + ": " + property);
        }
    }

    private String required(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required database setting: " + property);
        }
        return value;
    }
}
