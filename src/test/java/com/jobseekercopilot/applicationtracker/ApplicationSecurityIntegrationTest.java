package com.jobseekercopilot.applicationtracker;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.controller.ApplicationRecordController;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentWorkflowRepository;
import com.jobseekercopilot.applicationtracker.security.ApplicationOwnerResolver;
import com.jobseekercopilot.applicationtracker.security.ApplicationServiceIdentityFilter;
import com.jobseekercopilot.applicationtracker.service.DocumentReferenceVerifier;
import com.jobseekercopilot.applicationtracker.service.DocumentStoreWorkflowClient;
import com.jobseekercopilot.applicationtracker.service.ApplicationAccountLifecycleService;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "environment-data.enabled=true",
        "environment-data.allowed-environments=e2e"
})
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
class ApplicationSecurityIntegrationTest {

    private static final String PRODUCER_TOKEN =
            "test-only-application-producer-token-32-bytes";
    private static final String READER_TOKEN =
            "test-only-application-reader-token-32-bytes";
    private static final String ENVIRONMENT_DATA_TOKEN =
            "test-only-environment-data-token-32-bytes";
    private static final UUID CV_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COVER_LETTER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REPLACEMENT_CV_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID FOREIGN_CV_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

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

    @Autowired
    private ApplicationDocumentWorkflowRepository workflowRepository;

    @MockBean
    private DocumentReferenceVerifier documentReferenceVerifier;

    @MockBean
    private DocumentStoreWorkflowClient documentStoreWorkflowClient;

    @MockBean
    private ApplicationAccountLifecycleService accountLifecycleService;

    @BeforeEach
    void cleanDatabase() {
        workflowRepository.deleteAll();
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
    void validUserTokenStrictlyBindsCreateAndListToItsSubject() throws Exception {
        CreateApplicationRequest aliceRequest = createRequest("alice");

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice"))
                        .header("X-User-Id", "victim")
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "victim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(aliceRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("alice"));

        mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("victim"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Application record not found."));

        mockMvc.perform(get("/api/v1/applications/user/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        assertTrue(repository.findByUserId("victim").isEmpty());
    }

    @Test
    void accountLifecycleTokensAreConfinedToTheInternalErasureRoute()
            throws Exception {
        String lifecycleToken = JWKS.accountLifecycleToken(
                "lifecycle-owner", "operation-123");

        mockMvc.perform(delete("/internal/account-lifecycle/personal-data")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + JWKS.validToken("lifecycle-owner")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/applications/account-export")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + lifecycleToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/internal/account-lifecycle/personal-data")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + lifecycleToken))
                .andExpect(status().isNoContent());

        verify(accountLifecycleService).erase("lifecycle-owner");
    }

    @Test
    void savedApplicationAttachesDocumentsAndProgressesOnlyWithCompletePair()
            throws Exception {
        String owner = "saved-owner";
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId(owner)
                .jobId("saved-job-1")
                .canonicalJobId("saved-job-1")
                .provider("REED")
                .externalJobId("reed-saved-1")
                .jobTitle("Platform Engineer")
                .companyName("Example Ltd")
                .provenance(ApplicationProvenance.MANUAL)
                .initialStatus(ApplicationStatus.SAVED)
                .build();

        MvcResult created = mockMvc.perform(post("/api/v1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SAVED"))
                .andExpect(jsonPath("$.appliedAt").doesNotExist())
                .andReturn();
        UUID applicationId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .path("id")
                .asText());

        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                applicationId)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                ApplicationOwnerResolver.OWNER_HEADER,
                                owner)
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "producer-cannot-apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"APPLIED","expectedVersion":0}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        attachDocument(owner, applicationId, "CV", CV_ID);

        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                applicationId)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                ApplicationOwnerResolver.OWNER_HEADER,
                                owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DOCUMENTS_GENERATED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Document reference is not eligible for this application."));

        attachDocument(
                owner,
                applicationId,
                "COVER_LETTER",
                COVER_LETTER_ID);

        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                applicationId)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                ApplicationOwnerResolver.OWNER_HEADER,
                                owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DOCUMENTS_GENERATED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("DOCUMENTS_GENERATED"))
                .andExpect(jsonPath("$.appliedAt").doesNotExist())
                .andExpect(jsonPath("$.cvDocumentId")
                        .value(CV_ID.toString()))
                .andExpect(jsonPath("$.coverLetterDocumentId")
                        .value(COVER_LETTER_ID.toString()));

        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/status",
                                applicationId)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(
                                ApplicationOwnerResolver.OWNER_HEADER,
                                owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DOCUMENTS_GENERATED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("DOCUMENTS_GENERATED"));

        mockMvc.perform(get(
                                "/api/v1/applications/{id}/history",
                                applicationId)
                        .header(HttpHeaders.AUTHORIZATION, authorization(owner))
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus")
                        .value("DOCUMENTS_GENERATED"))
                .andExpect(jsonPath("$.reconciled").value(true))
                .andExpect(jsonPath("$.events", hasSize(4)))
                .andExpect(jsonPath("$.events[0].eventType")
                        .value("APPLICATION_CREATED"))
                .andExpect(jsonPath("$.events[0].toStatus").value("SAVED"))
                .andExpect(jsonPath("$.events[1].eventType")
                        .value("DOCUMENT_REFERENCE_CHANGED"))
                .andExpect(jsonPath("$.events[2].eventType")
                        .value("DOCUMENT_REFERENCE_CHANGED"))
                .andExpect(jsonPath("$.events[3].eventType")
                        .value("STATUS_CHANGED"))
                .andExpect(jsonPath("$.events[3].fromStatus").value("SAVED"))
                .andExpect(jsonPath("$.events[3].toStatus")
                        .value("DOCUMENTS_GENERATED"));
    }

    @Test
    void foreignAndMissingResourcesHaveTheSameStableDenialAcrossOperations() throws Exception {
        ApplicationRecord alice = saveApplication("alice", ApplicationStatus.DOCUMENTS_GENERATED);
        UUID missing = UUID.randomUUID();

        MvcResult foreign = mockMvc.perform(get("/api/v1/applications/{id}", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult absent = mockMvc.perform(get("/api/v1/applications/{id}", missing)
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound())
                .andReturn();
        assertSameDenial(foreign, absent);

        mockMvc.perform(get("/api/v1/applications/user/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Application record not found."));

        mockMvc.perform(get("/api/v1/applications/document/{documentId}", alice.getCvDocumentId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/applications/{id}/status", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob"))
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "foreign-apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("APPLIED")
                                        .expectedVersion(0L)
                                        .build())))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/applications/{id}/document-reference", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateDocumentReferenceRequest.builder()
                                        .documentType("CV")
                                        .documentId(FOREIGN_CV_ID)
                                        .build())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/applications/{id}/withdraw-generated", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/applications/{id}", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob")))
                .andExpect(status().isNotFound());

        ApplicationRecord unchanged =
                repository.findByIdAndUserId(alice.getId(), "alice").orElseThrow();
        assertEquals(ApplicationStatus.DOCUMENTS_GENERATED, unchanged.getStatus());
        assertEquals(alice.getCvDocumentId(), unchanged.getCvDocumentId());
    }

    @Test
    void invalidAccessTokensFailUniformlyWithoutTokenOrProviderDetails() throws Exception {
        List<String> invalidTokens = List.of(
                "not-a-jwt",
                JWKS.expiredToken("expired-subject"),
                JWKS.forgedKnownKeyToken("forged-subject"),
                JWKS.unknownKeyToken("unknown-key-subject"),
                JWKS.wrongAlgorithmToken("wrong-algorithm-subject"),
                JWKS.wrongIssuerToken("wrong-issuer-subject"),
                JWKS.wrongAudienceToken("wrong-audience-subject"),
                JWKS.refreshTokenType("wrong-type-subject"),
                JWKS.missingSubjectToken());

        assertAuthenticationFailure(null);
        for (String token : invalidTokens) {
            assertAuthenticationFailure(token);
        }
    }

    @Test
    void serviceIdentitiesAreDistinctOwnerScopedAndLeastPrivilege() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/applications")
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andReturn();
        UUID id = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString())
                        .path("id")
                        .asText());

        mockMvc.perform(post("/api/v1/applications")
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("alice"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/applications/user/{userId}", "alice")
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/applications/{id}", id)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "alice"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/applications/{id}", id)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Application owner is required for service requests."));

        mockMvc.perform(patch("/api/v1/applications/{id}/document-reference", id)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateDocumentReferenceRequest.builder()
                                        .documentType("CV")
                                        .documentId(REPLACEMENT_CV_ID)
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cvDocumentId")
                        .value(REPLACEMENT_CV_ID.toString()));

        mockMvc.perform(patch("/api/v1/applications/{id}/document-reference", id)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateDocumentReferenceRequest.builder()
                                        .documentType("CV")
                                        .documentId(REPLACEMENT_CV_ID)
                                        .build())))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "alice")
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "producer-apply-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("APPLIED")
                                        .expectedVersion(0L)
                                        .build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("APPLIED")
                                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Application owner is required for service requests."));

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "bob")
                        .header(
                                ApplicationRecordController.IDEMPOTENCY_KEY_HEADER,
                                "foreign-owner-apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("APPLIED")
                                        .expectedVersion(0L)
                                        .build())))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                READER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder()
                                        .status("APPLIED")
                                        .build())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/applications/user/{userId}", "alice")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/applications/user/{userId}", "alice")
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN,
                                READER_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void environmentDataIdentityIsIndependentAndHealthIsPublic() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String scenarioId = "security-check-v1";
        ApplicationRecord fixture =
                saveApplication(ownerId.toString(), ApplicationStatus.DOCUMENTS_GENERATED);
        fixture.setFixtureScenarioId(scenarioId);
        repository.save(fixture);

        mockMvc.perform(get(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                scenarioId,
                                ownerId)
                        .header(
                                ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER,
                                ENVIRONMENT_DATA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(1));

        mockMvc.perform(get(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                scenarioId,
                                ownerId)
                        .header(HttpHeaders.AUTHORIZATION, authorization("alice")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                scenarioId,
                                ownerId)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/applications/user/{userId}", "alice")
                        .header(
                                ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER,
                                ENVIRONMENT_DATA_TOKEN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private void assertAuthenticationFailure(String token) throws Exception {
        var request = get("/api/v1/applications/user/{userId}", "alice");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Valid authentication is required."))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        if (token != null) {
            assertFalse(response.contains(token));
        }
        assertFalse(response.contains("127.0.0.1"));
        assertFalse(response.contains("subject"));
        assertFalse(response.contains("Jwt"));
    }

    private void assertSameDenial(MvcResult first, MvcResult second) throws Exception {
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertEquals(firstBody.path("status"), secondBody.path("status"));
        assertEquals(firstBody.path("message"), secondBody.path("message"));
        assertEquals("Application record not found.", firstBody.path("message").asText());
    }

    private ApplicationRecord saveApplication(String owner, ApplicationStatus status) {
        return repository.save(ApplicationRecord.builder()
                .userId(owner)
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(status)
                .build());
    }

    private CreateApplicationRequest createRequest(String owner) {
        return CreateApplicationRequest.builder()
                .userId(owner)
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID)
                .coverLetterDocumentId(COVER_LETTER_ID)
                .build();
    }

    private void attachDocument(
            String owner,
            UUID applicationId,
            String type,
            UUID documentId)
            throws Exception {
        mockMvc.perform(patch(
                                "/api/v1/applications/{id}/document-reference",
                                applicationId)
                        .header(
                                ApplicationServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateDocumentReferenceRequest.builder()
                                        .documentType(type)
                                        .documentId(documentId)
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAVED"));
    }

    private DocumentVersionReference reference(
            UUID id, String jobId, DocumentType type) {
        return DocumentVersionReference.builder()
                .documentId(id)
                .documentFamilyId(id)
                .jobId(jobId)
                .documentType(type)
                .version(id.equals(REPLACEMENT_CV_ID) ? 2 : 1)
                .contentSha256(
                        type == DocumentType.CV ? "a".repeat(64) : "b".repeat(64))
                .build();
    }

    private static String authorization(String subject) {
        return "Bearer " + JWKS.validToken(subject);
    }
}
