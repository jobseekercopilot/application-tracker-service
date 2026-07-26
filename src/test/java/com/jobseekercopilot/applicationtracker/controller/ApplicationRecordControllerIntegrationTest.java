package com.jobseekercopilot.applicationtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.TestJwksServer;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationRecordControllerIntegrationTest {

    private static final TestJwksServer JWKS = new TestJwksServer();

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

    @Test
    void createApplication_ShouldReturn201() throws Exception {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
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
    void getApplicationById_WhenExists_ShouldReturn200() throws Exception {
        ApplicationRecord saved = repository.save(ApplicationRecord.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
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
                .andExpect(jsonPath("$.cvDocumentId").value("cv-123"))
                .andExpect(jsonPath("$.coverLetterDocumentId").value("cl-456"))
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
}
