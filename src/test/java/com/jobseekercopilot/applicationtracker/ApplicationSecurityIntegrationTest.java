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
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.security.ApplicationOwnerResolver;
import com.jobseekercopilot.applicationtracker.security.ApplicationServiceIdentityFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder().status("APPLIED").build())))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/applications/{id}/document-reference", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, authorization("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateDocumentReferenceRequest.builder()
                                        .documentType("CV")
                                        .documentId("foreign-cv")
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
                                        .documentId("replacement-cv")
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cvDocumentId").value("replacement-cv"));

        mockMvc.perform(patch("/api/v1/applications/{id}/document-reference", id)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, READER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateDocumentReferenceRequest.builder()
                                        .documentType("CV")
                                        .documentId("reader-cannot-write")
                                        .build())))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/applications/{id}/status", id)
                        .header(ApplicationServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(ApplicationOwnerResolver.OWNER_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                UpdateStatusRequest.builder().status("APPLIED").build())))
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .build();
    }

    private static String authorization(String subject) {
        return "Bearer " + JWKS.validToken(subject);
    }
}
