package com.jobseekercopilot.applicationtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.TestJwksServer;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
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
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentWorkflowRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentReconciliationRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private ApplicationDocumentWorkflowRepository workflowRepository;

    @Autowired
    private ApplicationDocumentReconciliationRepository
            reconciliationRepository;

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
    void nhsJobsSourceIdentityIsPreservedAcrossCreateReadAndList()
            throws Exception {
        String ownerId = "nhs-owner";
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId(ownerId)
                .jobId("canonical-nhs-c123")
                .canonicalJobId("canonical-nhs-c123")
                .provider("NHS_JOBS")
                .externalJobId("C123")
                .listingUrl("https://www.jobs.nhs.uk/candidate/jobadvert/C123")
                .applyUrl("https://www.jobs.nhs.uk/candidate/jobadvert/C123")
                .attributionLabel("Vacancy source: NHS Jobs")
                .attributionSourceUrl("https://www.jobs.nhs.uk/")
                .licenceUrl("https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/")
                .disclaimer("NHS Jobs does not endorse Job Seeker Copilot.")
                .jobTitle("Community Staff Nurse")
                .companyName("Example NHS Trust")
                .location("London")
                .provenance(ApplicationProvenance.MANUAL)
                .build();

        String body = mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization(ownerId))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "nhs-c123-tracking-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canonicalJobId")
                        .value("canonical-nhs-c123"))
                .andExpect(jsonPath("$.provider").value("NHS_JOBS"))
                .andExpect(jsonPath("$.externalJobId").value("C123"))
                .andExpect(jsonPath("$.listingUrl").value("https://www.jobs.nhs.uk/candidate/jobadvert/C123"))
                .andExpect(jsonPath("$.applyUrl").value("https://www.jobs.nhs.uk/candidate/jobadvert/C123"))
                .andExpect(jsonPath("$.attributionLabel").value("Vacancy source: NHS Jobs"))
                .andExpect(jsonPath("$.attributionSourceUrl").value("https://www.jobs.nhs.uk/"))
                .andExpect(jsonPath("$.licenceUrl").value("https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/"))
                .andExpect(jsonPath("$.disclaimer").value("NHS Jobs does not endorse Job Seeker Copilot."))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID applicationId =
                UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/v1/applications/{id}", applicationId)
                        .header(HttpHeaders.AUTHORIZATION, authorization(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalJobId")
                        .value("canonical-nhs-c123"))
                .andExpect(jsonPath("$.provider").value("NHS_JOBS"))
                .andExpect(jsonPath("$.externalJobId").value("C123"))
                .andExpect(jsonPath("$.listingUrl").value("https://www.jobs.nhs.uk/candidate/jobadvert/C123"))
                .andExpect(jsonPath("$.applyUrl").value("https://www.jobs.nhs.uk/candidate/jobadvert/C123"))
                .andExpect(jsonPath("$.attributionLabel").value("Vacancy source: NHS Jobs"))
                .andExpect(jsonPath("$.attributionSourceUrl").value("https://www.jobs.nhs.uk/"))
                .andExpect(jsonPath("$.licenceUrl").value("https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/"))
                .andExpect(jsonPath("$.disclaimer").value("NHS Jobs does not endorse Job Seeker Copilot."));

        mockMvc.perform(get("/api/v1/applications/user/{userId}", ownerId)
                        .header(HttpHeaders.AUTHORIZATION, authorization(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].canonicalJobId")
                        .value("canonical-nhs-c123"))
                .andExpect(jsonPath("$[0].provider").value("NHS_JOBS"))
                .andExpect(jsonPath("$[0].externalJobId").value("C123"))
                .andExpect(jsonPath("$[0].listingUrl").value("https://www.jobs.nhs.uk/candidate/jobadvert/C123"))
                .andExpect(jsonPath("$[0].applyUrl").value("https://www.jobs.nhs.uk/candidate/jobadvert/C123"))
                .andExpect(jsonPath("$[0].attributionLabel").value("Vacancy source: NHS Jobs"))
                .andExpect(jsonPath("$[0].attributionSourceUrl").value("https://www.jobs.nhs.uk/"))
                .andExpect(jsonPath("$[0].licenceUrl").value("https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/"))
                .andExpect(jsonPath("$[0].disclaimer").value("NHS Jobs does not endorse Job Seeker Copilot."));

        ApplicationRecord persisted =
                repository.findById(applicationId).orElseThrow();
        assertThat(persisted.getProvider()).isEqualTo("NHS_JOBS");
        assertThat(persisted.getExternalJobId()).isEqualTo("C123");
        assertThat(persisted.getListingUrl()).isEqualTo("https://www.jobs.nhs.uk/candidate/jobadvert/C123");
        assertThat(persisted.getApplyUrl()).isEqualTo("https://www.jobs.nhs.uk/candidate/jobadvert/C123");
        assertThat(persisted.getAttributionLabel()).isEqualTo("Vacancy source: NHS Jobs");
        assertThat(persisted.getAttributionSourceUrl()).isEqualTo("https://www.jobs.nhs.uk/");
        assertThat(persisted.getLicenceUrl()).isEqualTo("https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/");
        assertThat(persisted.getDisclaimer()).isEqualTo("NHS Jobs does not endorse Job Seeker Copilot.");
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
                        .queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId")
                        .value(applicationId.toString()))
                .andExpect(jsonPath("$.currentStatus").value("OFFER"))
                .andExpect(jsonPath("$.currentVersion").value(3))
                .andExpect(jsonPath("$.reconciled").value(true))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].eventType")
                        .value("APPLICATION_CREATED"))
                .andExpect(jsonPath("$.events[0].actorType").value("USER"))
                .andExpect(jsonPath("$.events[0].actorId").value(ownerId))
                .andExpect(jsonPath("$.events[0].source").value("USER"))
                .andExpect(jsonPath("$.events[1].eventType")
                        .value("STATUS_CHANGED"))
                .andExpect(jsonPath("$.events[1].fromStatus")
                        .value("DOCUMENTS_GENERATED"))
                .andExpect(jsonPath("$.events[1].toStatus").value("APPLIED"))
                .andExpect(jsonPath("$.events[1].occurredAt")
                        .value(appliedAt.toString()))
                .andExpect(jsonPath("$.events[1].reason").value("Applied"));

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
                .andExpect(jsonPath("$.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(1));
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
    void updateStatus_RepeatedStatus_ShouldBeIdempotentEvenWithStaleVersion()
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateStatusRequest.builder()
                                .status("APPLIED")
                                .expectedVersion(99L)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.version").value(0));

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
                .build();
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
