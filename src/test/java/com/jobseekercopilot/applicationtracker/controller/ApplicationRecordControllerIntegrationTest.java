package com.jobseekercopilot.applicationtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.TestJwksServer;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceUnavailableException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.service.DocumentReferenceVerifier;
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

import java.time.LocalDateTime;
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
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationRecordControllerIntegrationTest {

    private static final TestJwksServer JWKS = new TestJwksServer();
    private static final UUID CV_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COVER_LETTER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

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

    @MockBean
    private DocumentReferenceVerifier documentReferenceVerifier;

    @BeforeEach
    void setUp() {
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        mockMvc.perform(post("/api/v1/applications/{id}/withdraw-generated", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(saved.getId().toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.withdrawn").value(true));

        mockMvc.perform(get("/api/v1/applications/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123")))
                .andExpect(status().isNotFound());
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
}
