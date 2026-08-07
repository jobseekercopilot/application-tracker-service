package com.jobseekercopilot.applicationtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.TestJwksServer;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentEvidenceProvenance;
import com.jobseekercopilot.applicationtracker.dto.DocumentGroundingState;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.EvidenceRevisionReference;
import com.jobseekercopilot.applicationtracker.dto.EvidenceSection;
import com.jobseekercopilot.applicationtracker.dto.ValidatedClaimLedger;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentReconciliation;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.DocumentReferenceReconciliationStatus;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceUnavailableException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationEventRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentSelectionCommandRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentWorkflowRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentReconciliationRepository;
import com.jobseekercopilot.applicationtracker.repository.DocumentAvailabilityProjectionRepository;
import com.jobseekercopilot.applicationtracker.security.ApplicationOwnerResolver;
import com.jobseekercopilot.applicationtracker.security.ApplicationServiceIdentityFilter;
import com.jobseekercopilot.applicationtracker.service.DocumentStoreWorkflowClient;
import com.jobseekercopilot.applicationtracker.service.DocumentReferenceVerifier;
import com.jobseekercopilot.applicationtracker.service.ApplicationReplacementWorkflowService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationRecordControllerIntegrationTest {

    private static final TestJwksServer JWKS = new TestJwksServer();
    private static final UUID CV_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COVER_LETTER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String PRODUCER_TOKEN =
            "test-only-application-producer-token-32-bytes";

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("application-tracker.security.jwk-set-uri", JWKS::jwkSetUri);
        registry.add("application-tracker.security.issuer", () -> TestJwksServer.ISSUER);
        registry.add("application-tracker.security.audience", () -> TestJwksServer.AUDIENCE);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationRecordRepository repository;

    @Autowired
    private ApplicationEventRepository eventRepository;

    @Autowired
    private ApplicationDocumentSelectionCommandRepository selectionCommandRepository;

    @Autowired
    private ApplicationDocumentWorkflowRepository workflowRepository;

    @Autowired
    private ApplicationDocumentReconciliationRepository
            reconciliationRepository;

    @Autowired
    private DocumentAvailabilityProjectionRepository
            availabilityProjectionRepository;

    @Autowired
    private ApplicationReplacementWorkflowService replacementWorkflowService;

    @MockBean
    private DocumentReferenceVerifier documentReferenceVerifier;

    @MockBean
    private DocumentStoreWorkflowClient documentStoreWorkflowClient;

    @BeforeEach
    void setUp() {
        workflowRepository.deleteAll();
        reconciliationRepository.deleteAll();
        selectionCommandRepository.deleteAll();
        availabilityProjectionRepository.deleteAll();
        repository.deleteAll();
        when(documentReferenceVerifier.verify(
                        anyString(),
                        any(UUID.class),
                        anyString(),
                        any(DocumentType.class)))
                .thenAnswer(invocation -> reference(
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
    }

    @Test
    void atomicDocumentSelectionSupportsEveryOptionalCombination()
            throws Exception {
        ApplicationRecord application = savedApplication("selection-owner");

        String cvOnly = saveSelections(
                application,
                "selection-owner",
                "selection-cv-only",
                application.getVersion(),
                selected(CV_ID),
                omitted());
        long version = objectMapper.readTree(cvOnly).get("version").asLong();
        assertThat(objectMapper.readTree(cvOnly).get("cvDocumentId").asText())
                .isEqualTo(CV_ID.toString());
        assertThat(objectMapper.readTree(cvOnly).get("coverLetterDocumentId").isNull())
                .isTrue();

        String coverOnly = saveSelections(
                application,
                "selection-owner",
                "selection-cover-only",
                version,
                omitted(),
                selected(COVER_LETTER_ID));
        version = objectMapper.readTree(coverOnly).get("version").asLong();
        assertThat(objectMapper.readTree(coverOnly).get("cvDocumentId").isNull())
                .isTrue();
        assertThat(objectMapper.readTree(coverOnly)
                        .get("coverLetterDocumentId").asText())
                .isEqualTo(COVER_LETTER_ID.toString());

        String both = saveSelections(
                application,
                "selection-owner",
                "selection-both",
                version,
                selected(CV_ID),
                selected(COVER_LETTER_ID));
        version = objectMapper.readTree(both).get("version").asLong();
        assertThat(objectMapper.readTree(both).get("cvDocumentId").asText())
                .isEqualTo(CV_ID.toString());
        assertThat(objectMapper.readTree(both)
                        .get("coverLetterDocumentId").asText())
                .isEqualTo(COVER_LETTER_ID.toString());

        String none = saveSelections(
                application,
                "selection-owner",
                "selection-none",
                version,
                omitted(),
                omitted());
        assertThat(objectMapper.readTree(none).get("cvDocumentId").isNull())
                .isTrue();
        assertThat(objectMapper.readTree(none)
                        .get("coverLetterDocumentId").isNull())
                .isTrue();
        assertThat(repository.findById(application.getId()).orElseThrow()
                        .getStatus())
                .isEqualTo(ApplicationStatus.SAVED);
    }

    @Test
    void atomicSelectionContractRejectsPartialOrInconsistentSlots()
            throws Exception {
        ApplicationRecord application = savedApplication("validation-owner");
        String path = "/api/v1/applications/" + application.getId()
                + "/document-selections";

        mockMvc.perform(put(path)
                        .header(HttpHeaders.AUTHORIZATION, authorization("validation-owner"))
                        .header("Idempotency-Key", "missing-cover-slot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cvSelection":{"state":"OMITTED"},"expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(path)
                        .header(HttpHeaders.AUTHORIZATION, authorization("validation-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectionJson(0, omitted(), omitted())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(path)
                        .header(HttpHeaders.AUTHORIZATION, authorization("validation-owner"))
                        .header("Idempotency-Key", "selected-without-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cvSelection":{"state":"SELECTED"},"coverLetterSelection":{"state":"OMITTED"},"expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(path)
                        .header(HttpHeaders.AUTHORIZATION, authorization("validation-owner"))
                        .header("Idempotency-Key", "omitted-with-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cvSelection":{"state":"OMITTED","documentId":"11111111-1111-4111-8111-111111111111"},"coverLetterSelection":{"state":"OMITTED"},"expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(repository.findById(application.getId()).orElseThrow()
                        .getVersion())
                .isZero();
    }

    @Test
    void appliedApplicationRejectsSelectionWithoutDocumentVerification()
            throws Exception {
        ApplicationRecord application = savedApplication("applied-selection-owner");
        application.setStatus(ApplicationStatus.APPLIED);
        application.setAppliedAt(LocalDateTime.now());
        repository.saveAndFlush(application);

        mockMvc.perform(put(
                                "/api/v1/applications/{id}/document-selections",
                                application.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("applied-selection-owner"))
                        .header("Idempotency-Key", "applied-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectionJson(
                                application.getVersion(),
                                selected(CV_ID),
                                omitted())))
                .andExpect(status().isBadRequest());

        verify(documentReferenceVerifier, org.mockito.Mockito.never()).verify(
                anyString(), any(UUID.class), anyString(), any(DocumentType.class));
        assertThat(repository.findById(application.getId()).orElseThrow()
                        .getCvDocumentId())
                .isNull();
    }

    @Test
    void staleSelectionReturnsAuthoritativeStateWithoutPartialMutation()
            throws Exception {
        ApplicationRecord application = savedApplication("stale-owner");
        saveSelections(
                application,
                "stale-owner",
                "stale-first",
                0,
                selected(CV_ID),
                omitted());

        mockMvc.perform(put(
                                "/api/v1/applications/{id}/document-selections",
                                application.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("stale-owner"))
                        .header("Idempotency-Key", "stale-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectionJson(
                                0,
                                omitted(),
                                selected(COVER_LETTER_ID))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentApplication.version").value(1))
                .andExpect(jsonPath("$.currentApplication.cvDocumentId")
                        .value(CV_ID.toString()))
                .andExpect(jsonPath("$.currentApplication.coverLetterDocumentId")
                        .doesNotExist());

        ApplicationRecord persisted =
                repository.findById(application.getId()).orElseThrow();
        assertThat(persisted.getCvDocumentId()).isEqualTo(CV_ID.toString());
        assertThat(persisted.getCoverLetterDocumentId()).isNull();
    }

    @Test
    void selectionReplayReturnsStoredOutcomeOnceAndKeyReuseConflicts()
            throws Exception {
        ApplicationRecord application = savedApplication("retry-owner");
        String first = saveSelections(
                application,
                "retry-owner",
                "retry-selection",
                0,
                selected(CV_ID),
                omitted());
        String replay = saveSelections(
                application,
                "retry-owner",
                "retry-selection",
                0,
                selected(CV_ID),
                omitted());

        assertThat(objectMapper.readTree(replay))
                .isEqualTo(objectMapper.readTree(first));
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        application.getId(), "retry-owner"))
                .isEqualTo(1);

        mockMvc.perform(put(
                                "/api/v1/applications/{id}/document-selections",
                                application.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("retry-owner"))
                        .header("Idempotency-Key", "retry-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectionJson(0, omitted(), omitted())))
                .andExpect(status().isConflict());
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        application.getId(), "retry-owner"))
                .isEqualTo(1);
    }

    @Test
    void invalidOrCrossOwnerSelectionNeverMutatesApplication()
            throws Exception {
        ApplicationRecord application = savedApplication("secure-owner");
        UUID rejectedDocument =
                UUID.fromString("77777777-7777-4777-8777-777777777777");
        when(documentReferenceVerifier.verify(
                        "secure-owner",
                        rejectedDocument,
                        "selection-job",
                        DocumentType.CV))
                .thenThrow(new InvalidDocumentReferenceException());

        mockMvc.perform(put(
                                "/api/v1/applications/{id}/document-selections",
                                application.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("secure-owner"))
                        .header("Idempotency-Key", "invalid-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectionJson(
                                0, selected(rejectedDocument), omitted())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(
                                "/api/v1/applications/{id}/document-selections",
                                application.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("other-owner"))
                        .header("Idempotency-Key", "cross-owner-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectionJson(0, selected(CV_ID), omitted())))
                .andExpect(status().isNotFound());

        ApplicationRecord persisted =
                repository.findById(application.getId()).orElseThrow();
        assertThat(persisted.getCvDocumentId()).isNull();
        assertThat(persisted.getCoverLetterDocumentId()).isNull();
    }

    @Test
    void createApplication_ShouldReturn201() throws Exception {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID)
                .coverLetterDocumentId(COVER_LETTER_ID)
                .build();

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("user-123"))
                .andExpect(jsonPath("$.jobId").value("job-456"))
                .andExpect(jsonPath("$.jobTitle").value("Java Developer"))
                .andExpect(jsonPath("$.companyName").value("Example Ltd"))
                .andExpect(jsonPath("$.status").value("DOCUMENTS_GENERATED"))
                .andExpect(jsonPath(
                                "$.cvDocumentReference.evidenceProvenance.profileRevisionId")
                        .value("33333333-3333-4333-8333-333333333333"))
                .andExpect(jsonPath(
                                "$.coverLetterDocumentReference.evidenceProvenance.evidenceSnapshotId")
                        .value("44444444-4444-4444-8444-444444444444"))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void documentReferenceReconciliationIsOwnerScopedAndVisible()
            throws Exception {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("reconciliation-owner")
                .jobId("reconciliation-job")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID)
                .coverLetterDocumentId(COVER_LETTER_ID)
                .build();

        String response = mockMvc.perform(post("/api/v1/applications")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("reconciliation-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String applicationId =
                objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get(
                        "/api/v1/applications/{id}/document-reference-reconciliation",
                        applicationId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("reconciliation-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId")
                        .value(applicationId))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.issueCodes", hasSize(0)))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty());

        mockMvc.perform(get(
                        "/api/v1/applications/{id}/document-reference-reconciliation",
                        applicationId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("different-owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createManualApplicationWithoutDocuments_ShouldReturnAppliedRecord()
            throws Exception {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("manual-owner")
                .jobId("manual-job-1")
                .canonicalJobId("manual-job-1")
                .provider("manual")
                .externalJobId("manual-job-1")
                .jobTitle("Support Engineer")
                .companyName("Example Ltd")
                .location("London")
                .provenance(ApplicationProvenance.MANUAL)
                .build();

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("manual-owner"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "manual-job-1-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provenance").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.cvDocumentId").doesNotExist())
                .andExpect(jsonPath("$.coverLetterDocumentId").doesNotExist());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void lifecycleCommandsExposeOrderedPaginatedOwnerScopedHistory()
            throws Exception {
        String ownerId = "history-owner";
        String body = mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization(ownerId))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "history-job-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                requestForOwner(ownerId))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID applicationId =
                UUID.fromString(objectMapper.readTree(body).get("id").asText());
        Instant appliedAt =
                Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(10);
        Instant interviewAt = appliedAt.plusSeconds(10);
        Instant offerAt = interviewAt.plusSeconds(10);

        transition(applicationId, ownerId, "APPLIED", 0, appliedAt, "Applied");
        transition(
                applicationId,
                ownerId,
                "INTERVIEW",
                1,
                interviewAt,
                "First-stage interview");
        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                applicationId)
                        .header(HttpHeaders.AUTHORIZATION, authorization(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("OFFER")
                                        .expectedVersion(2L)
                                        .occurredAt(appliedAt.plusSeconds(5))
                                        .reason("Inconsistent earlier offer")
                                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "occurredAt cannot be before the latest recorded application event."));
        transition(applicationId, ownerId, "OFFER", 2, offerAt, "Offer received");

        mockMvc.perform(get(
                                "/api/v1/applications/{id}/history",
                                applicationId)
                        .header(HttpHeaders.AUTHORIZATION, authorization(ownerId))
                        .queryParam("page", "0")
                        .queryParam("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId")
                        .value(applicationId.toString()))
                .andExpect(jsonPath("$.currentStatus").value("OFFER"))
                .andExpect(jsonPath("$.currentVersion").value(3))
                .andExpect(jsonPath("$.reconciled").value(true))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[0].eventType")
                        .value("APPLICATION_CREATED"))
                .andExpect(jsonPath("$.events[0].actorType").value("USER"))
                .andExpect(jsonPath("$.events[0].actorId").value(ownerId))
                .andExpect(jsonPath("$.events[0].source").value("USER"))
                .andExpect(jsonPath("$.events[1].eventType")
                        .value("APPLICATION_DOCUMENTS_FROZEN"))
                .andExpect(jsonPath("$.events[2].eventType")
                        .value("STATUS_CHANGED"))
                .andExpect(jsonPath("$.events[2].fromStatus")
                        .value("DOCUMENTS_GENERATED"))
                .andExpect(jsonPath("$.events[2].toStatus").value("APPLIED"))
                .andExpect(jsonPath("$.events[2].occurredAt")
                        .value(appliedAt.toString()))
                .andExpect(jsonPath("$.events[2].reason").value("Applied"));

        mockMvc.perform(get(
                                "/api/v1/applications/{id}/history",
                                applicationId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("different-owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void repeatedIdempotencyKeyAndPayload_ShouldReturnSameRecord() throws Exception {
        String firstBody = mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "generated-job-456-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String firstId = objectMapper.readTree(firstBody).get("id").asText();

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "generated-job-456-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        UUID.fromString(firstId), "user-123"))
                .isEqualTo(1);
    }

    @Test
    void legacyProducerWithoutHeader_ShouldReplayDeterministically() throws Exception {
        String firstId = objectMapper.readTree(mockMvc.perform(
                                post("/api/v1/applications")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                authorization("legacy-owner"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(
                                                requestForOwner("legacy-owner"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();

        mockMvc.perform(post("/api/v1/applications")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("legacy-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                requestForOwner("legacy-owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameCanonicalJobCanBeTrackedByDifferentOwners() throws Exception {
        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("owner-one"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "owner-one-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                requestForOwner("owner-one"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("owner-two"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "owner-two-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                requestForOwner("owner-two"))))
                .andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayload_ShouldReturn409() throws Exception {
        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "generated-job-456-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated());

        CreateApplicationRequest conflicting = validRequest();
        conflicting.setJobTitle("Different title");
        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "generated-job-456-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflicting)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Idempotency key was already used for a different application command."));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void differentKeyForSameCanonicalJob_ShouldReturn409() throws Exception {
        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "generated-job-456-first")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "generated-job-456-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "An application for this canonical job is already tracked for the owner."));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void createApplication_WithMissingFields_ShouldReturn400() throws Exception {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .build();

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createApplication_WithIneligibleDocument_ShouldReturn400WithoutWriting()
            throws Exception {
        when(documentReferenceVerifier.verify(
                        "user-123", CV_ID, "job-456", DocumentType.CV))
                .thenThrow(new InvalidDocumentReferenceException());

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Document reference is not eligible for this application."));
        org.junit.jupiter.api.Assertions.assertEquals(0, repository.count());
    }

    @Test
    void createApplication_WhenDocumentStoreUnavailable_ShouldReturn503WithoutWriting()
            throws Exception {
        when(documentReferenceVerifier.verify(
                        "user-123", CV_ID, "job-456", DocumentType.CV))
                .thenThrow(new DocumentReferenceUnavailableException());

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Document reference validation is temporarily unavailable."));
        org.junit.jupiter.api.Assertions.assertEquals(0, repository.count());
    }

    @Test
    void getApplicationById_WhenExists_ShouldReturn200() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .cvDocumentFamilyId(CV_ID.toString())
                .cvDocumentVersion(1)
                .cvDocumentContentSha256("a".repeat(64))
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .coverLetterDocumentFamilyId(COVER_LETTER_ID.toString())
                .coverLetterDocumentVersion(1)
                .coverLetterDocumentContentSha256("b".repeat(64))
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());
        mockMvc.perform(get("/api/v1/applications/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.status").value("DOCUMENTS_GENERATED"));
    }

    @Test
    void purgedAvailabilityKeepsExactIdentityAndScrubsHashesAndEvidence()
            throws Exception {
        LocalDateTime frozenAt = LocalDateTime.now().minusDays(3);
        ApplicationRecord saved = repository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId("availability-owner")
                        .jobId("job-availability")
                        .jobTitle("Java Developer")
                        .companyName("Example Ltd")
                        .cvDocumentId(CV_ID.toString())
                        .cvDocumentFamilyId(CV_ID.toString())
                        .cvDocumentVersion(7)
                        .cvDocumentContentSha256("a".repeat(64))
                        .cvDocumentEvidenceProvenance(provenance())
                        .cvDocumentGroundingState(
                                DocumentGroundingState
                                        .AI_GENERATED_EVIDENCE_VALIDATED)
                        .applicationUsedCvDocumentId(CV_ID.toString())
                        .applicationUsedCvDocumentFamilyId(CV_ID.toString())
                        .applicationUsedCvDocumentVersion(7)
                        .applicationUsedCvDocumentContentSha256("a".repeat(64))
                        .applicationUsedCvEvidenceProvenance(provenance())
                        .applicationUsedCvGroundingState(
                                DocumentGroundingState
                                        .AI_GENERATED_EVIDENCE_VALIDATED)
                        .applicationUsedCvState(
                                com.jobseekercopilot.applicationtracker.entity
                                        .FrozenDocumentSelectionState.SELECTED)
                        .applicationUsedAt(frozenAt)
                        .status(ApplicationStatus.APPLIED)
                        .build());
        String occurredAt = LocalDateTime.now().minusMinutes(1).toString();

        mockMvc.perform(put(
                                "/api/v1/applications/document/{documentId}/availability",
                                CV_ID)
                        .headers(producerHeaders("availability-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"availability":"PURGED","unavailableReason":"PURGED_BY_APPROVED_RETENTION_POLICY","occurredAt":"%s"}
                                """.formatted(occurredAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("PURGED"))
                .andExpect(jsonPath("$.unavailableReason").value(
                        "PURGED_BY_APPROVED_RETENTION_POLICY"));

        mockMvc.perform(get("/api/v1/applications/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION,
                                authorization("availability-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cvDocumentReference.documentId")
                        .value(CV_ID.toString()))
                .andExpect(jsonPath("$.cvDocumentReference.version").value(7))
                .andExpect(jsonPath("$.cvDocumentReference.availability")
                        .value("PURGED"))
                .andExpect(jsonPath("$.cvDocumentReference.unavailableReason")
                        .value("PURGED_BY_APPROVED_RETENTION_POLICY"))
                .andExpect(jsonPath("$.cvDocumentReference.contentSha256")
                        .doesNotExist())
                .andExpect(jsonPath("$.cvDocumentReference.evidenceProvenance")
                        .doesNotExist())
                .andExpect(jsonPath(
                                "$.applicationUsedCvDocumentReference.documentId")
                        .value(CV_ID.toString()))
                .andExpect(jsonPath(
                                "$.applicationUsedCvDocumentReference.contentSha256")
                        .doesNotExist());

        ApplicationRecord scrubbed = repository.findById(saved.getId())
                .orElseThrow();
        assertThat(scrubbed.getCvDocumentContentSha256()).isNull();
        assertThat(scrubbed.getCvDocumentEvidenceProvenance()).isNull();
        assertThat(scrubbed.getApplicationUsedCvDocumentContentSha256())
                .isNull();
        assertThat(scrubbed.getApplicationUsedCvEvidenceProvenance()).isNull();

        mockMvc.perform(put(
                                "/api/v1/applications/document/{documentId}/availability",
                                CV_ID)
                        .headers(producerHeaders("availability-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"availability":"AVAILABLE","occurredAt":"%s"}
                                """.formatted(LocalDateTime.now())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getApplicationById_WhenNotExists_ShouldReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/applications/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isNotFound());
    }

    @Test
    void getApplicationsForUser_ShouldReturnList() throws Exception {
        repository.save(ApplicationRecord.builder()
                .userId("user-test")
                .jobId("job-1")
                .jobTitle("Developer")
                .companyName("Company A")
                .cvDocumentId("cv-1")
                .coverLetterDocumentId("cl-1")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        repository.save(ApplicationRecord.builder()
                .userId("user-test")
                .jobId("job-2")
                .jobTitle("Engineer")
                .companyName("Company B")
                .cvDocumentId("cv-2")
                .coverLetterDocumentId("cl-2")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        mockMvc.perform(get("/api/v1/applications/user/{userId}", "user-test")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void getApplicationByDocumentId_WhenExists_ShouldReturn200() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        mockMvc.perform(get("/api/v1/applications/document/{documentId}", "cv-123")
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.cvDocumentId").value("cv-123"));
    }

    @Test
    void exactDocumentAssociationsDistinguishDraftAndFrozenWithoutCrossOwnerLeak()
            throws Exception {
        UUID frozenApplicationId = repository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId("association-owner")
                        .jobId("job-frozen")
                        .jobTitle("Frozen role")
                        .companyName("Example Ltd")
                        .applicationUsedCvDocumentId(CV_ID.toString())
                        .applicationUsedCvDocumentFamilyId(CV_ID.toString())
                        .applicationUsedCvDocumentVersion(2)
                        .applicationUsedCvDocumentContentSha256("a".repeat(64))
                        .applicationUsedCvState(
                                com.jobseekercopilot.applicationtracker.entity
                                        .FrozenDocumentSelectionState.SELECTED)
                        .applicationUsedAt(LocalDateTime.now().minusDays(2))
                        .status(ApplicationStatus.APPLIED)
                        .build())
                .getId();
        UUID draftApplicationId = repository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId("association-owner")
                        .jobId("job-draft")
                        .jobTitle("Draft role")
                        .companyName("Example Ltd")
                        .cvDocumentId(CV_ID.toString())
                        .cvDocumentFamilyId(CV_ID.toString())
                        .cvDocumentVersion(2)
                        .cvDocumentContentSha256("a".repeat(64))
                        .status(ApplicationStatus.SAVED)
                        .build())
                .getId();

        mockMvc.perform(get(
                                "/api/v1/applications/document/{documentId}/associations",
                                CV_ID)
                        .headers(producerHeaders("association-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.associationCount").value(2))
                .andExpect(jsonPath("$.associations[?(@.applicationId == '%s')].associationState"
                                .formatted(frozenApplicationId))
                        .value(org.hamcrest.Matchers.contains("FROZEN_USED")))
                .andExpect(jsonPath("$.associations[?(@.applicationId == '%s')].associationState"
                                .formatted(draftApplicationId))
                        .value(org.hamcrest.Matchers.contains("DRAFT_SELECTED")));

        mockMvc.perform(get(
                                "/api/v1/applications/document/{documentId}/associations",
                                CV_ID)
                        .headers(producerHeaders("different-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.associationCount").value(0))
                .andExpect(jsonPath("$.associations").isEmpty());
    }

    @Test
    void updateStatus_WithValidStatus_ShouldReturn200() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .cvDocumentFamilyId(CV_ID.toString())
                .cvDocumentVersion(1)
                .cvDocumentContentSha256("a".repeat(64))
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .coverLetterDocumentFamilyId(COVER_LETTER_ID.toString())
                .coverLetterDocumentVersion(1)
                .coverLetterDocumentContentSha256("b".repeat(64))
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());
        saveHealthyReconciliation(saved);

        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("APPLIED")
                .expectedVersion(saved.getVersion())
                .build();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "apply-valid-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.cvDocumentId").value(CV_ID.toString()))
                .andExpect(jsonPath("$.coverLetterDocumentId")
                        .value(COVER_LETTER_ID.toString()))
                .andExpect(jsonPath("$.applicationUsedCvDocumentReference.documentId")
                        .value(CV_ID.toString()))
                .andExpect(jsonPath("$.applicationUsedCoverLetterDocumentReference.documentId")
                        .value(COVER_LETTER_ID.toString()))
                .andExpect(jsonPath("$.applicationUsedCvState").value("SELECTED"))
                .andExpect(jsonPath("$.applicationUsedCoverLetterState")
                        .value("SELECTED"))
                .andExpect(jsonPath("$.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(1));

        long eventsAfterApply = eventRepository.countByApplicationIdAndUserId(
                saved.getId(), "user-123");
        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "apply-valid-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        saved.getId(), "user-123"))
                .isEqualTo(eventsAfterApply);
    }

    @Test
    void updateStatus_WithUnhealthyDocumentReferences_ShouldReturn409()
            throws Exception {
        ApplicationRecord saved = repository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId("unhealthy-owner")
                        .jobId("unhealthy-job")
                        .canonicalJobId("unhealthy-job")
                        .provenance(ApplicationProvenance.GENERATED)
                        .jobTitle("Java Developer")
                        .companyName("Example Ltd")
                        .cvDocumentId(CV_ID.toString())
                        .coverLetterDocumentId(
                                COVER_LETTER_ID.toString())
                        .status(ApplicationStatus.DOCUMENTS_GENERATED)
                        .build());
        reconciliationRepository.saveAndFlush(
                ApplicationDocumentReconciliation.builder()
                        .applicationId(saved.getId())
                        .userId(saved.getUserId())
                        .status(
                                DocumentReferenceReconciliationStatus.INVALID)
                        .issueCodes("CURRENT_CV_INVALID")
                        .applicationRecordVersion(saved.getVersion())
                        .build());

        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("unhealthy-owner"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "apply-unhealthy-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("APPLIED")
                                        .expectedVersion(saved.getVersion())
                                        .build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Application document references are not currently verified. Retry after reconciliation."));

        assertThat(repository.findById(saved.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);
    }

    @Test
    void updateStatus_WithDisallowedJump_ShouldReturn409AndKeepRecord() throws Exception {
        ApplicationRecord saved = repository.saveAndFlush(ApplicationRecord.builder()
                .userId("transition-owner")
                .jobId("transition-job")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("transition-cv")
                .coverLetterDocumentId("transition-cl")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());
        LocalDateTime updatedAt =
                repository.findById(saved.getId()).orElseThrow().getUpdatedAt();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("transition-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateStatusRequest.builder()
                                .status("OFFER")
                                .expectedVersion(saved.getVersion())
                                .build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Application status transition from DOCUMENTS_GENERATED to OFFER is not allowed."));

        ApplicationRecord unchanged = repository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);
        assertThat(unchanged.getVersion()).isZero();
        assertThat(unchanged.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void updateStatus_WithStaleVersion_ShouldReturn409AndKeepRecord() throws Exception {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 7, 26, 18, 0);
        ApplicationRecord saved = repository.saveAndFlush(ApplicationRecord.builder()
                .userId("stale-owner")
                .jobId("stale-job")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("stale-cv")
                .coverLetterDocumentId("stale-cl")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(appliedAt)
                .build());
        LocalDateTime updatedAt =
                repository.findById(saved.getId()).orElseThrow().getUpdatedAt();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("stale-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateStatusRequest.builder()
                                .status("INTERVIEW")
                                .expectedVersion(saved.getVersion() + 1)
                                .build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Application was changed by another request. Refresh and retry."));

        ApplicationRecord unchanged = repository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(unchanged.getVersion()).isZero();
        assertThat(unchanged.getAppliedAt()).isEqualTo(appliedAt);
        assertThat(unchanged.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void updateStatus_NewCommandCannotRewriteAlreadyAppliedRecord()
            throws Exception {
        ApplicationRecord saved = repository.saveAndFlush(ApplicationRecord.builder()
                .userId("retry-owner")
                .jobId("retry-job")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("retry-cv")
                .coverLetterDocumentId("retry-cl")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.of(2026, 7, 26, 18, 0))
                .build());
        LocalDateTime updatedAt =
                repository.findById(saved.getId()).orElseThrow().getUpdatedAt();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("retry-owner"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "different-apply-command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateStatusRequest.builder()
                                .status("APPLIED")
                                .expectedVersion(99L)
                                .build())))
                .andExpect(status().isConflict());

        ApplicationRecord unchanged = repository.findById(saved.getId()).orElseThrow();
        assertThat(unchanged.getVersion()).isZero();
        assertThat(unchanged.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void updateStatus_FromTerminalState_ShouldReturn409() throws Exception {
        ApplicationRecord saved = repository.saveAndFlush(ApplicationRecord.builder()
                .userId("terminal-owner")
                .jobId("terminal-job")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("terminal-cv")
                .coverLetterDocumentId("terminal-cl")
                .status(ApplicationStatus.ACCEPTED)
                .appliedAt(LocalDateTime.of(2026, 7, 26, 18, 0))
                .build());

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("terminal-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateStatusRequest.builder()
                                .status("INTERVIEW")
                                .expectedVersion(saved.getVersion())
                                .build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Application status transition from ACCEPTED to INTERVIEW is not allowed."));

        assertThat(repository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.ACCEPTED);
    }

    @Test
    void updateStatus_WithNegativeExpectedVersion_ShouldReturn400() throws Exception {
        ApplicationRecord saved = repository.saveAndFlush(ApplicationRecord.builder()
                .userId("validation-owner")
                .jobId("validation-job")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("validation-cv")
                .coverLetterDocumentId("validation-cl")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("validation-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateStatusRequest.builder()
                                .status("APPLIED")
                                .expectedVersion(-1L)
                                .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));

        assertThat(repository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);
    }

    @Test
    void updateStatus_WithInvalidStatus_ShouldReturn400() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("INVALID")
                .build();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteApplication_ShouldReturn204() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        mockMvc.perform(delete("/api/v1/applications/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isNoContent());
    }

    @Test
    void withdrawGeneratedApplication_WhenDocumentsGenerated_ShouldReturnNewAndRemoveRecord() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        mockMvc.perform(post("/api/v1/applications/{id}/withdraw-generated", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(saved.getId().toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.withdrawn").value(true))
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.operationStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.retryable").value(false));

        mockMvc.perform(get("/api/v1/applications/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isNotFound());
    }

    @Test
    void documentReplacementRemainsVisibleAndReplaysUntilTrackerCommits()
            throws Exception {
        String ownerId = "replacement-owner";
        UUID replacementId =
                UUID.fromString("33333333-3333-4333-8333-333333333333");
        ApplicationRecord saved = repository.save(
                ApplicationRecord.builder()
                        .userId(ownerId)
                        .jobId("job-replacement")
                        .jobTitle("Platform Engineer")
                        .companyName("Example Ltd")
                        .cvDocumentId(CV_ID.toString())
                        .cvDocumentFamilyId(CV_ID.toString())
                        .cvDocumentVersion(1)
                        .cvDocumentContentSha256("a".repeat(64))
                        .coverLetterDocumentId(
                                COVER_LETTER_ID.toString())
                        .coverLetterDocumentFamilyId(
                                COVER_LETTER_ID.toString())
                        .coverLetterDocumentVersion(1)
                        .coverLetterDocumentContentSha256(
                                "b".repeat(64))
                        .status(ApplicationStatus.DOCUMENTS_GENERATED)
                        .build());
        String beginRequest = """
                {
                  "documentType": "CV",
                  "requestSha256": "%s"
                }
                """.formatted("c".repeat(64));

        String accepted = mockMvc.perform(post(
                                "/api/v1/applications/{id}/document-replacements",
                                saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(beginRequest))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentType").value("CV"))
                .andExpect(jsonPath("$.sourceDocumentId")
                        .value(CV_ID.toString()))
                .andExpect(jsonPath("$.operationStatus").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String operationId = objectMapper.readTree(accepted)
                .path("operationId")
                .asText();

        mockMvc.perform(post(
                                "/api/v1/applications/{id}/document-replacements",
                                saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(beginRequest))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(operationId));
        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/document-replacements/{operationId}/replacement-document",
                                saved.getId(),
                                operationId)
                        .headers(producerHeaders(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementDocumentId":"%s"}
                                """.formatted(replacementId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replacementDocumentId")
                        .value(replacementId.toString()))
                .andExpect(jsonPath("$.operationStatus").value("RUNNING"));
        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/document-replacements/{operationId}/recovery-required",
                                saved.getId(),
                                operationId)
                        .headers(producerHeaders(ownerId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationStatus")
                        .value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(true));

        assertThat(repository.findById(saved.getId()).orElseThrow()
                        .getCvDocumentId())
                .isEqualTo(CV_ID.toString());

        replacementWorkflowService.reconcileRegisteredReplacements();

        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/document-replacements/{operationId}/complete",
                                saved.getId(),
                                operationId)
                        .headers(producerHeaders(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.replacementDocumentId")
                        .value(replacementId.toString()))
                .andExpect(jsonPath("$.cvDocumentId")
                        .value(replacementId.toString()))
                .andExpect(jsonPath("$.retryable").value(false));

        ApplicationRecord completed =
                repository.findById(saved.getId()).orElseThrow();
        assertThat(completed.getCvDocumentId())
                .isEqualTo(replacementId.toString());
        assertThat(completed.getActiveDocumentWorkflowId()).isNull();

        mockMvc.perform(get(
                                "/api/v1/applications/{id}/document-replacements/{operationId}",
                                saved.getId(),
                                operationId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationStatus").value("COMPLETED"));
        mockMvc.perform(post(
                                "/api/v1/applications/{id}/document-replacements",
                                saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(beginRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId))
                .andExpect(jsonPath("$.operationStatus").value("COMPLETED"));
    }

    @Test
    void withdrawGeneratedApplication_WhenStoreFails_ShouldRemainVisibleAndResumeSameOperation()
            throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("recovery-owner")
                .jobId("job-recovery")
                .jobTitle("Recovery Engineer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());
        doThrow(new com.jobseekercopilot.applicationtracker.service
                        .DocumentStoreWorkflowException(
                                "DOCUMENT_STORE_UNAVAILABLE", true, null))
                .doNothing()
                .when(documentStoreWorkflowClient)
                .softDeleteGeneratedDocuments(
                        anyString(),
                        any(UUID.class),
                        any(UUID.class),
                        anyList());

        String pendingBody = mockMvc.perform(
                        post("/api/v1/applications/{id}/withdraw-generated",
                                saved.getId())
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        authorization("recovery-owner")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.withdrawn").value(false))
                .andExpect(jsonPath("$.status")
                        .value("DOCUMENTS_GENERATED"))
                .andExpect(jsonPath("$.operationStatus")
                        .value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.recoveryCode")
                        .value("DOCUMENT_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String operationId =
                objectMapper.readTree(pendingBody).path("operationId").asText();

        mockMvc.perform(get("/api/v1/applications/{id}", saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("recovery-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("DOCUMENTS_GENERATED"));
        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("recovery-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("APPLIED")
                                        .build())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/applications/{id}/withdraw-generated",
                                saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("recovery-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId))
                .andExpect(jsonPath("$.operationStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.withdrawn").value(true));
        mockMvc.perform(get("/api/v1/applications/{id}/withdraw-generated",
                                saved.getId())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization("recovery-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId))
                .andExpect(jsonPath("$.operationStatus").value("COMPLETED"));

        assertThat(repository.findById(saved.getId())).isEmpty();
        verify(documentStoreWorkflowClient, times(2))
                .softDeleteGeneratedDocuments(
                        anyString(),
                        any(UUID.class),
                        any(UUID.class),
                        anyList());
    }

    @Test
    void withdrawGeneratedApplication_WhenApplied_ShouldReturn400AndKeepRecord() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.APPLIED)
                .build());

        mockMvc.perform(post("/api/v1/applications/{id}/withdraw-generated", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/applications/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    private ApplicationRecord savedApplication(String ownerId) {
        return repository.saveAndFlush(ApplicationRecord.builder()
                .userId(ownerId)
                .jobId("selection-job")
                .canonicalJobId("selection-job")
                .provider("MANUAL")
                .externalJobId("selection-job")
                .provenance(ApplicationProvenance.MANUAL)
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .status(ApplicationStatus.SAVED)
                .build());
    }

    private String saveSelections(
            ApplicationRecord application,
            String ownerId,
            String idempotencyKey,
            long expectedVersion,
            String cvSelection,
            String coverLetterSelection) throws Exception {
        return mockMvc.perform(put(
                                "/api/v1/applications/{id}/document-selections",
                                application.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization(ownerId))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectionJson(
                                expectedVersion,
                                cvSelection,
                                coverLetterSelection)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String selectionJson(
            long expectedVersion,
            String cvSelection,
            String coverLetterSelection) {
        return """
                {"cvSelection":%s,"coverLetterSelection":%s,"expectedVersion":%d}
                """.formatted(
                        cvSelection, coverLetterSelection, expectedVersion);
    }

    private String selected(UUID documentId) {
        return """
                {"state":"SELECTED","documentId":"%s"}
                """.formatted(documentId);
    }

    private String omitted() {
        return "{\"state\":\"OMITTED\"}";
    }

    private static String authorization(String subject) {
        return "Bearer " + JWKS.validToken(subject);
    }

    private void saveHealthyReconciliation(
            ApplicationRecord application) {
        LocalDateTime now = LocalDateTime.now();
        reconciliationRepository.saveAndFlush(
                ApplicationDocumentReconciliation.builder()
                        .applicationId(application.getId())
                        .userId(application.getUserId())
                        .status(
                                DocumentReferenceReconciliationStatus.HEALTHY)
                        .checkedAt(now)
                        .lastHealthyAt(now)
                        .applicationRecordVersion(
                                application.getVersion())
                        .build());
    }

    private HttpHeaders producerHeaders(String ownerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(
                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                PRODUCER_TOKEN);
        headers.set(ApplicationOwnerResolver.OWNER_HEADER, ownerId);
        return headers;
    }

    private DocumentVersionReference reference(
            UUID id, String jobId, DocumentType type) {
        return DocumentVersionReference.builder()
                .documentId(id)
                .documentFamilyId(id)
                .jobId(jobId)
                .documentType(type)
                .version(1)
                .contentSha256(
                        type == DocumentType.CV ? "a".repeat(64) : "b".repeat(64))
                .evidenceProvenance(provenance())
                .groundingState(
                        DocumentGroundingState
                                .AI_GENERATED_EVIDENCE_VALIDATED)
                .build();
    }

    private DocumentEvidenceProvenance provenance() {
        UUID evidenceId =
                UUID.fromString("55555555-5555-4555-8555-555555555555");
        return new DocumentEvidenceProvenance(
                UUID.fromString(
                        "33333333-3333-4333-8333-333333333333"),
                "c".repeat(64),
                UUID.fromString(
                        "44444444-4444-4444-8444-444444444444"),
                "d".repeat(64),
                List.of(new EvidenceRevisionReference(
                        evidenceId,
                        UUID.fromString(
                                "66666666-6666-4666-8666-666666666666"),
                        2,
                        EvidenceSection.EMPLOYMENT,
                        "e".repeat(64))),
                List.of(EvidenceSection.EMPLOYMENT),
                new ValidatedClaimLedger(
                        UUID.fromString(
                                "99999999-9999-4999-8999-999999999999"),
                        "f".repeat(64),
                        "2.0.0",
                        "3.0.0"),
                OffsetDateTime.parse("2026-07-29T03:00:00Z"));
    }

    private CreateApplicationRequest validRequest() {
        return requestForOwner("user-123");
    }

    private CreateApplicationRequest requestForOwner(String owner) {
        return CreateApplicationRequest.builder()
                .userId(owner)
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID)
                .coverLetterDocumentId(COVER_LETTER_ID)
                .build();
    }

    private void transition(
            UUID applicationId,
            String ownerId,
            String statusValue,
            long expectedVersion,
            Instant occurredAt,
            String reason) throws Exception {
        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                applicationId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization(ownerId))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "APPLIED".equals(statusValue)
                                        ? "apply-history-command"
                                        : "unused-status-command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status(statusValue)
                                        .expectedVersion(expectedVersion)
                                        .occurredAt(occurredAt)
                                        .reason(reason)
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(statusValue));
    }
}
