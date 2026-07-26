package com.jobseekercopilot.applicationtracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.RollbackException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class PostgresJpaSchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15.18-alpine3.23")
                    .withDatabaseName("application_tracker_jpa")
                    .withUsername("application_tracker")
                    .withPassword("application_tracker");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add(
                "spring.datasource.hikari.data-source-properties.sslmode",
                () -> "disable");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add(
                "spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add(
                "application-tracker.database.production-safety-check",
                () -> "false");
    }

    @Autowired
    private ApplicationRecordRepository repository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void flywaySchemaMatchesJpaAndPersistsAnAssignedUuid() {
        ApplicationRecord record = ApplicationRecord.builder()
                .userId("synthetic-jpa-owner")
                .jobId("synthetic-jpa-job")
                .jobTitle("Synthetic Java Developer")
                .companyName("Example Employer")
                .location("Manchester")
                .cvDocumentId("synthetic-cv")
                .coverLetterDocumentId("synthetic-cover-letter")
                .build();

        ApplicationRecord saved = repository.saveAndFlush(record);

        assertThat(saved.getId()).isEqualTo(record.getId());
        assertThat(repository.findByIdAndUserId(saved.getId(), record.getUserId()))
                .get()
                .extracting(ApplicationRecord::getJobTitle)
                .isEqualTo("Synthetic Java Developer");
    }

    @Test
    void concurrentWritersCannotSilentlyOverwriteTheCommittedStatus() {
        ApplicationRecord saved = repository.saveAndFlush(ApplicationRecord.builder()
                .userId("synthetic-concurrency-owner")
                .jobId("synthetic-concurrency-job")
                .jobTitle("Synthetic Java Developer")
                .companyName("Example Employer")
                .cvDocumentId("synthetic-cv")
                .coverLetterDocumentId("synthetic-cover-letter")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        EntityManager firstWriter = entityManagerFactory.createEntityManager();
        EntityManager secondWriter = entityManagerFactory.createEntityManager();
        try {
            firstWriter.getTransaction().begin();
            secondWriter.getTransaction().begin();
            ApplicationRecord first = firstWriter.find(
                    ApplicationRecord.class, saved.getId());
            ApplicationRecord second = secondWriter.find(
                    ApplicationRecord.class, saved.getId());
            assertThat(first.getVersion()).isZero();
            assertThat(second.getVersion()).isZero();

            first.setStatus(ApplicationStatus.APPLIED);
            second.setStatus(ApplicationStatus.INTERVIEW);
            firstWriter.getTransaction().commit();

            assertThatThrownBy(() -> secondWriter.getTransaction().commit())
                    .isInstanceOf(RollbackException.class)
                    .hasRootCauseInstanceOf(
                            org.hibernate.StaleObjectStateException.class);
        } finally {
            if (firstWriter.getTransaction().isActive()) {
                firstWriter.getTransaction().rollback();
            }
            if (secondWriter.getTransaction().isActive()) {
                secondWriter.getTransaction().rollback();
            }
            firstWriter.close();
            secondWriter.close();
        }

        ApplicationRecord committed = repository.findById(saved.getId()).orElseThrow();
        assertThat(committed.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(committed.getVersion()).isEqualTo(1);
    }
}
