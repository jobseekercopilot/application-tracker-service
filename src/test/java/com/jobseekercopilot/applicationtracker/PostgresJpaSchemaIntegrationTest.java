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
import com.jobseekercopilot.applicationtracker.dto.DocumentSelectionCommand;
import com.jobseekercopilot.applicationtracker.dto.DocumentSelectionState;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.DocumentAvailabilityState;
import com.jobseekercopilot.applicationtracker.dto.DocumentSourceType;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentAvailabilityRequest;
import com.jobseekercopilot.applicationtracker.dto.SaveDocumentSelectionsRequest;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationEventRepository;
import com.jobseekercopilot.applicationtracker.repository.DocumentAvailabilityProjectionRepository;
import com.jobseekercopilot.applicationtracker.service.ApplicationCreationResult;
import com.jobseekercopilot.applicationtracker.service.ApplicationHistoryService;
import com.jobseekercopilot.applicationtracker.service.ApplicationRecordService;
import com.jobseekercopilot.applicationtracker.service.ApplicationCommandActor;
import com.jobseekercopilot.applicationtracker.service.ApplicationDocumentSelectionService;
import com.jobseekercopilot.applicationtracker.service.DocumentReferenceVerifier;
import com.jobseekercopilot.applicationtracker.service.DocumentAvailabilityProjectionService;
import com.jobseekercopilot.applicationtracker.service.ApplicationAccountLifecycleService;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.RollbackException;
import java.util.List;
import java.sql.SQLException;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private ApplicationEventRepository eventRepository;

    @Autowired
    private ApplicationHistoryService historyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationRecordService service;

    @Autowired
    private ApplicationDocumentSelectionService documentSelectionService;

    @Autowired
    private DocumentAvailabilityProjectionService availabilityProjectionService;

    @Autowired
    private DocumentAvailabilityProjectionRepository availabilityProjectionRepository;

    @Autowired
    private ApplicationAccountLifecycleService accountLifecycleService;

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
                            .ownerId(invocation.getArgument(0))
                            .sourceType(DocumentSourceType.GENERATED)
                            .build();
                });
        when(documentReferenceVerifier.verify(
                        anyString(),
                        any(UUID.class),
                        anyString(),
                        any(UUID.class),
                        any(DocumentType.class)))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(1);
                    String jobId = invocation.getArgument(2);
                    DocumentType type = invocation.getArgument(4);
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
                            .ownerId(invocation.getArgument(0))
                            .sourceType(DocumentSourceType.GENERATED)
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
    void accountErasureUsesTheNarrowAppendOnlyOverrideWithinOneTransaction() {
        String owner = "synthetic-account-erasure-owner";
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId(owner)
                .jobId("synthetic-account-erasure-job")
                .provenance(ApplicationProvenance.MANUAL)
                .canonicalJobId("synthetic-account-erasure-job")
                .provider("TEST")
                .externalJobId("synthetic-account-erasure-job")
                .jobTitle("Synthetic role")
                .companyName("Example Employer")
                .build();
        ApplicationCreationResult created = service.createApplication(
                owner, "synthetic-account-erasure-operation", request);
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        created.application().getId(), owner))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "delete from application_events where user_id = ?", owner))
                .isInstanceOf(DataAccessException.class);

        accountLifecycleService.erase(owner);

        assertThat(repository.findById(created.application().getId())).isEmpty();
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        created.application().getId(), owner))
                .isZero();
    }

    @Test
    void postgresProjectionRetainsMinimalTombstoneAndCannotResurrectPurgedContent() {
        String ownerId = "synthetic-purged-owner";
        UUID documentId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        ApplicationRecord saved = repository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId(ownerId)
                        .jobId("synthetic-purged-job")
                        .jobTitle("Synthetic role")
                        .companyName("Example Employer")
                        .cvDocumentId(documentId.toString())
                        .cvDocumentFamilyId(familyId.toString())
                        .cvDocumentVersion(4)
                        .cvDocumentContentSha256("a".repeat(64))
                        .cvDocumentSourceType(DocumentSourceType.UPLOADED)
                        .cvDocumentOriginalContentSha256("b".repeat(64))
                        .cvDocumentSelectedAt(LocalDateTime.now().minusDays(2))
                        .applicationUsedCvDocumentId(documentId.toString())
                        .applicationUsedCvDocumentFamilyId(familyId.toString())
                        .applicationUsedCvDocumentVersion(4)
                        .applicationUsedCvDocumentContentSha256("a".repeat(64))
                        .applicationUsedCvDocumentSourceType(
                                DocumentSourceType.UPLOADED)
                        .applicationUsedCvDocumentOriginalContentSha256(
                                "b".repeat(64))
                        .applicationUsedCvDocumentSelectedAt(
                                LocalDateTime.now().minusDays(2))
                        .status(ApplicationStatus.APPLIED)
                        .build());

        availabilityProjectionService.update(
                ownerId,
                documentId,
                new UpdateDocumentAvailabilityRequest(
                        DocumentAvailabilityState.PURGED,
                        "PURGED_BY_APPROVED_RETENTION_POLICY",
                        LocalDateTime.now()));

        ApplicationRecord persisted = repository.findById(saved.getId())
                .orElseThrow();
        assertThat(persisted.getCvDocumentContentSha256())
                .isEqualTo("a".repeat(64));
        assertThat(persisted.getApplicationUsedCvDocumentContentSha256())
                .isEqualTo("a".repeat(64));
        var response = service.getApplicationById(ownerId, saved.getId());
        assertThat(response.getCvDocumentReference().getDocumentId())
                .isEqualTo(documentId);
        assertThat(response.getCvDocumentReference().getDocumentFamilyId())
                .isEqualTo(familyId);
        assertThat(response.getCvDocumentReference().getVersion()).isEqualTo(4);
        assertThat(response.getCvDocumentReference().getOwnerId())
                .isEqualTo(ownerId);
        assertThat(response.getCvDocumentReference().getContentSha256())
                .isEqualTo("a".repeat(64));
        assertThat(response.getCvDocumentReference().getOriginalContentSha256())
                .isEqualTo("b".repeat(64));
        assertThat(response.getCvDocumentReference().getSourceType())
                .isEqualTo(DocumentSourceType.UPLOADED);
        assertThat(response.getCvDocumentReference().getSelectedAt()).isNotNull();
        assertThat(response.getCvDocumentReference().getAvailability())
                .isEqualTo(DocumentAvailabilityState.PURGED);
        assertThat(availabilityProjectionRepository
                        .findByOwnerIdAndDocumentId(ownerId, documentId))
                .isPresent();
        var exported = accountLifecycleService.export(ownerId);
        assertThat(exported.applications()).singleElement().satisfies(application -> {
            assertThat(application.getCvDocumentReference().getDocumentId())
                    .isEqualTo(documentId);
            assertThat(application.getCvDocumentReference().getOwnerId())
                    .isEqualTo(ownerId);
            assertThat(application.getCvDocumentReference().getContentSha256())
                    .isEqualTo("a".repeat(64));
            assertThat(application
                            .getCvDocumentReference()
                            .getOriginalContentSha256())
                    .isEqualTo("b".repeat(64));
            assertThat(application.getCvDocumentReference().getSourceType())
                    .isEqualTo(DocumentSourceType.UPLOADED);
            assertThat(application.getCvDocumentReference().getSelectedAt())
                    .isNotNull();
            assertThat(application.getCvDocumentReference().getAvailability())
                    .isEqualTo(DocumentAvailabilityState.PURGED);
        });

        assertThatThrownBy(() -> availabilityProjectionService.update(
                        ownerId,
                        documentId,
                        new UpdateDocumentAvailabilityRequest(
                                DocumentAvailabilityState.AVAILABLE,
                                null,
                                LocalDateTime.now().plusMinutes(1))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be restored");
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
    void staleSelectionWritersHaveOneWinnerAndWinnerRetryHasOneEvent()
            throws Exception {
        String ownerId = "synthetic-selection-concurrency-owner";
        ApplicationRecord saved = repository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId(ownerId)
                        .jobId("synthetic-selection-job")
                        .canonicalJobId("synthetic-selection-job")
                        .provider("MANUAL")
                        .externalJobId("synthetic-selection-job")
                        .provenance(ApplicationProvenance.MANUAL)
                        .jobTitle("Synthetic Java Developer")
                        .companyName("Example Employer")
                        .status(ApplicationStatus.SAVED)
                        .build());
        UUID firstDocument =
                UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID secondDocument =
                UUID.fromString("33333333-3333-4333-8333-333333333333");
        SaveDocumentSelectionsRequest firstRequest =
                selection(firstDocument, 0);
        SaveDocumentSelectionsRequest secondRequest =
                selection(secondDocument, 0);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = writers.submit(() -> runSelection(
                    start,
                    ownerId,
                    saved.getId(),
                    "selection-writer-one",
                    firstRequest));
            Future<Object> second = writers.submit(() -> runSelection(
                    start,
                    ownerId,
                    saved.getId(),
                    "selection-writer-two",
                    secondRequest));
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes.stream()
                            .filter(com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream()
                            .filter(com.jobseekercopilot.applicationtracker.exception.ApplicationSelectionVersionConflictException.class::isInstance))
                    .hasSize(1);

            ApplicationRecord persisted =
                    repository.findById(saved.getId()).orElseThrow();
            assertThat(persisted.getCvDocumentSourceType())
                    .isEqualTo(DocumentSourceType.GENERATED);
            assertThat(persisted.getCvDocumentSelectedAt()).isNotNull();
            UUID winningDocument = UUID.fromString(persisted.getCvDocumentId());
            String winningKey = winningDocument.equals(firstDocument)
                    ? "selection-writer-one"
                    : "selection-writer-two";
            SaveDocumentSelectionsRequest winningRequest =
                    winningDocument.equals(firstDocument)
                            ? firstRequest
                            : secondRequest;

            documentSelectionService.save(
                    ownerId,
                    saved.getId(),
                    winningKey,
                    winningRequest,
                    ApplicationCommandActor.user(ownerId));

            assertThat(persisted.getVersion()).isEqualTo(1);
            assertThat(eventRepository.countByApplicationIdAndUserId(
                            saved.getId(), ownerId))
                    .isEqualTo(1);
        } finally {
            writers.shutdownNow();
        }
    }

    @Test
    void simultaneousExactSelectionRetriesReturnOneStoredOutcome()
            throws Exception {
        String ownerId = "synthetic-selection-retry-owner";
        ApplicationRecord saved = repository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId(ownerId)
                        .jobId("synthetic-selection-retry-job")
                        .canonicalJobId("synthetic-selection-retry-job")
                        .provider("MANUAL")
                        .externalJobId("synthetic-selection-retry-job")
                        .provenance(ApplicationProvenance.MANUAL)
                        .jobTitle("Synthetic Java Developer")
                        .companyName("Example Employer")
                        .status(ApplicationStatus.SAVED)
                        .build());
        SaveDocumentSelectionsRequest request = selection(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                0);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService retries = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = retries.submit(() -> runSelection(
                    start,
                    ownerId,
                    saved.getId(),
                    "simultaneous-selection-retry",
                    request));
            Future<Object> second = retries.submit(() -> runSelection(
                    start,
                    ownerId,
                    saved.getId(),
                    "simultaneous-selection-retry",
                    request));
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes)
                    .allMatch(com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse.class::isInstance);
            assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
            assertThat(eventRepository.countByApplicationIdAndUserId(
                            saved.getId(), ownerId))
                    .isEqualTo(1);
            assertThat(repository.findById(saved.getId()).orElseThrow()
                            .getVersion())
                    .isEqualTo(1);
        } finally {
            retries.shutdownNow();
        }
    }

    private Object runSelection(
            CountDownLatch start,
            String ownerId,
            UUID applicationId,
            String idempotencyKey,
            SaveDocumentSelectionsRequest request) throws InterruptedException {
        start.await();
        try {
            return documentSelectionService.save(
                    ownerId,
                    applicationId,
                    idempotencyKey,
                    request,
                    ApplicationCommandActor.user(ownerId));
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private SaveDocumentSelectionsRequest selection(
            UUID cvDocumentId, long expectedVersion) {
        return SaveDocumentSelectionsRequest.builder()
                .cvSelection(DocumentSelectionCommand.builder()
                        .state(DocumentSelectionState.SELECTED)
                        .documentId(cvDocumentId)
                        .build())
                .coverLetterSelection(DocumentSelectionCommand.builder()
                        .state(DocumentSelectionState.OMITTED)
                        .build())
                .expectedVersion(expectedVersion)
                .build();
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
            assertThat(eventRepository.countByApplicationIdAndUserId(
                            first.application().getId(),
                            "synthetic-idempotent-owner"))
                    .isEqualTo(1);
            assertThat(historyService.getHistory(
                            "synthetic-idempotent-owner",
                            first.application().getId(),
                            0,
                            50)
                    .reconciled())
                    .isTrue();
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

    @Test
    void databaseTriggerRejectsEventRewritesAndDeletion() {
        ApplicationCreationResult result = service.createApplication(
                "synthetic-immutable-owner",
                "synthetic-immutable-attempt",
                CreateApplicationRequest.builder()
                        .userId("synthetic-immutable-owner")
                        .jobId("synthetic-immutable-job")
                        .canonicalJobId("synthetic-immutable-job")
                        .provider("MANUAL")
                        .externalJobId("synthetic-immutable-job")
                        .jobTitle("Synthetic Support Engineer")
                        .companyName("Example Employer")
                        .provenance(ApplicationProvenance.MANUAL)
                        .build());

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE application_events SET reason = ? WHERE application_id = ?",
                        "rewritten",
                        result.application().getId()))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM application_events WHERE application_id = ?",
                        result.application().getId()))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        result.application().getId(),
                        "synthetic-immutable-owner"))
                .isEqualTo(1);
    }
}
