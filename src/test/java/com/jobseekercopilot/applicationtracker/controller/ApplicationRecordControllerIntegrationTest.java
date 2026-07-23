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

import java.util.UUID;

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
                .build();

        mockMvc.perform(patch("/api/v1/applications/{id}/status", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("user-123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.cvDocumentId").value("cv-123"))
                .andExpect(jsonPath("$.coverLetterDocumentId").value("cl-456"))
                .andExpect(jsonPath("$.appliedAt").isNotEmpty());
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
