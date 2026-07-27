package com.jobseekercopilot.applicationtracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.service.ApplicationCreationResult;
import com.jobseekercopilot.applicationtracker.service.ApplicationRecordService;
import com.jobseekercopilot.applicationtracker.service.DocumentReferenceVerifier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.RollbackException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    @Autowired
    private ApplicationRecordService service;

    @MockBean
    private DocumentReferenceVerifier documentReferenceVerifier;

    @BeforeEach
    void documentReferences() {
        when(documentReferenceVerifier.verify(
                        anyString(),
                        any(UUID.class),
                        anyString(),
                        any(DocumentType.class)))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(1);
                    String jobId = invocation.getArgument(2);
                    DocumentType type = invocation.getArgument(3);
                    return DocumentVersionReference.builder()
                            .documentId(id)
                            .documentFamilyId(id)
                            .jobId(jobId)
                            .documentType(type)
                            .version(1)
                            .contentSha256(
                                    type == DocumentType.CV
                                            ? "a".repeat(64)
                                            : "b".repeat(64))
                            .build();
                });
    }

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

    @Test
    void concurrentCreateRetriesCommitOneApplicationAndReturnOneIdentity()
            throws Exception {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("synthetic-idempotent-owner")
                .jobId("synthetic-idempotent-job")
                .canonicalJobId("synthetic-idempotent-job")
                .provider("TEST")
                .externalJobId("synthetic-idempotent-job")
                .jobTitle("Synthetic Platform Engineer")
                .companyName("Example Employer")
                .cvDocumentId(
                        UUID.fromString("11111111-1111-4111-8111-111111111111"))
                .coverLetterDocumentId(
                        UUID.fromString("22222222-2222-4222-8222-222222222222"))
                .build();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ApplicationCreationResult>> attempts = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return service.createApplication(
                                "synthetic-idempotent-owner",
                                "synthetic-idempotent-attempt",
                                request);
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return service.createApplication(
                                "synthetic-idempotent-owner",
                                "synthetic-idempotent-attempt",
                                request);
                    }));
            ready.await();
            start.countDown();

            ApplicationCreationResult first = attempts.get(0).get();
            ApplicationCreationResult second = attempts.get(1).get();

            assertThat(first.application().getId())
                    .isEqualTo(second.application().getId());
            assertThat(List.of(first.created(), second.created()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(repository
                            .findByUserIdAndCanonicalJobIdAndFixtureScenarioIdIsNull(
                                    "synthetic-idempotent-owner",
                                    "synthetic-idempotent-job"))
                    .get()
                    .extracting(ApplicationRecord::getId)
                    .isEqualTo(first.application().getId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void manualApplicationPersistsWithoutDocumentReferences() {
        ApplicationCreationResult result = service.createApplication(
                "synthetic-manual-owner",
                "synthetic-manual-attempt",
                CreateApplicationRequest.builder()
                        .userId("synthetic-manual-owner")
                        .jobId("synthetic-manual-job")
                        .canonicalJobId("synthetic-manual-job")
                        .provider("MANUAL")
                        .externalJobId("synthetic-manual-job")
                        .jobTitle("Synthetic Support Engineer")
                        .companyName("Example Employer")
                        .provenance(ApplicationProvenance.MANUAL)
                        .build());

        ApplicationRecord persisted =
                repository.findById(result.application().getId()).orElseThrow();
        assertThat(persisted.getProvenance())
                .isEqualTo(ApplicationProvenance.MANUAL);
        assertThat(persisted.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(persisted.getAppliedAt()).isNotNull();
        assertThat(persisted.getCvDocumentId()).isNull();
        assertThat(persisted.getCoverLetterDocumentId()).isNull();
    }
}
