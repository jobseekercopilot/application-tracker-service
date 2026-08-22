package com.jobseekercopilot.applicationtracker.systemdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentGroundingState;
import com.jobseekercopilot.applicationtracker.entity.FrozenDocumentSelectionState;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.DocumentReferenceReconciliationStatus;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentReconciliationRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.security.ApplicationServiceIdentityFilter;
import com.jobseekercopilot.applicationtracker.service.ApplicationRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "environment-data.enabled=true",
        "environment-data.isolated-database=true",
        "environment-data.allowed-environments=e2e"
})
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
class SystemDataApplicationIntegrationTest {
    private static final String TOKEN = "test-only-environment-data-token-32-bytes";
    private static final String SCENARIO_A = "demo-ready-v1";
    private static final String SCENARIO_B = "empty-check-v1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationRecordRepository repository;

    @Autowired
    private ApplicationDocumentReconciliationRepository
            reconciliationRepository;

    @Autowired
    private ApplicationRecordService applicationRecordService;

    @BeforeEach
    void cleanDatabase() {
        reconciliationRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void deterministicSeedAndVerifyAreOwnerAndScenarioScoped() throws Exception {
        UUID ownerId = UUID.randomUUID();
        ApplicationRecord ordinary = repository.save(ordinaryApplication(ownerId));
        SystemDataApplicationSeedRecord generated =
                fixture(UUID.randomUUID(), ApplicationStatus.DOCUMENTS_GENERATED, 4);
        SystemDataApplicationSeedRecord applied =
                fixture(UUID.randomUUID(), ApplicationStatus.APPLIED, 3);
        SystemDataApplicationSeedRequest request =
                request(ownerId, SCENARIO_A, List.of(generated, applied));

        String firstResponse = seed(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(2))
                .andExpect(jsonPath("$.details.scenarioId").value(SCENARIO_A))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertFalse(firstResponse.contains(ownerId.toString()));

        LocalDateTime firstUpdatedAt =
                repository.findById(generated.id()).orElseThrow().getUpdatedAt();

        seed(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(2));

        assertEquals(3, repository.findByUserId(ownerId.toString()).size());
        assertEquals(
                firstUpdatedAt,
                repository.findById(generated.id()).orElseThrow().getUpdatedAt());
        assertEquals(
                DocumentReferenceReconciliationStatus.HEALTHY,
                reconciliationRepository.findById(generated.id())
                        .orElseThrow()
                        .getStatus());
        assertEquals(
                DocumentReferenceReconciliationStatus.HEALTHY,
                reconciliationRepository.findById(applied.id())
                        .orElseThrow()
                        .getStatus());
        ApplicationRecord seededGenerated =
                repository.findById(generated.id()).orElseThrow();
        assertEquals(
                DocumentGroundingState.LEGACY_UNSPECIFIED,
                seededGenerated.getCvDocumentGroundingState());
        assertEquals(
                FrozenDocumentSelectionState.UNKNOWN,
                seededGenerated.getApplicationUsedCvState());
        ApplicationRecord seededApplied =
                repository.findById(applied.id()).orElseThrow();
        assertEquals(
                DocumentGroundingState.LEGACY_UNSPECIFIED,
                seededApplied.getApplicationUsedCvGroundingState());
        assertEquals(
                FrozenDocumentSelectionState.SELECTED,
                seededApplied.getApplicationUsedCvState());
        assertEquals(
                FrozenDocumentSelectionState.SELECTED,
                seededApplied.getApplicationUsedCoverLetterState());
        assertEquals(ordinary.getId(), repository.findById(ordinary.getId()).orElseThrow().getId());

        mockMvc.perform(get(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                SCENARIO_A,
                                ownerId)
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(2))
                .andExpect(jsonPath("$.details.applications").value(2))
                .andExpect(jsonPath("$.details.byStatus.DOCUMENTS_GENERATED").value(1))
                .andExpect(jsonPath("$.details.byStatus.APPLIED").value(1))
                .andExpect(jsonPath("$.details.userId").doesNotExist());
    }

    @Test
    void environmentDataIdentityIsRequiredForEveryScenarioOperation() throws Exception {
        UUID ownerId = UUID.randomUUID();
        SystemDataApplicationSeedRequest request = request(
                ownerId,
                SCENARIO_A,
                List.of(fixture(
                        UUID.randomUUID(),
                        ApplicationStatus.DOCUMENTS_GENERATED,
                        4)));

        mockMvc.perform(post("/internal/system-data/v1/application-scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(
                        "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                        SCENARIO_A,
                        ownerId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete(
                        "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                        SCENARIO_A,
                        ownerId))
                .andExpect(status().isUnauthorized());

        assertEquals(0, repository.count());
    }

    @Test
    void resetDeletesOnlyTheRequestedFixtureBoundary() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        ApplicationRecord ordinary = repository.save(ordinaryApplication(ownerId));
        UUID scenarioARecord = UUID.randomUUID();
        UUID scenarioBRecord = UUID.randomUUID();
        UUID otherOwnerRecord = UUID.randomUUID();
        seed(request(
                        ownerId,
                        SCENARIO_A,
                        List.of(fixture(
                                scenarioARecord,
                                ApplicationStatus.DOCUMENTS_GENERATED,
                                4))))
                .andExpect(status().isOk());
        seed(request(
                        otherOwnerId,
                        SCENARIO_A,
                        List.of(fixture(
                                otherOwnerRecord,
                                ApplicationStatus.DOCUMENTS_GENERATED,
                                3))))
                .andExpect(status().isOk());
        seed(request(
                        ownerId,
                        SCENARIO_B,
                        List.of(fixture(
                                scenarioBRecord,
                                ApplicationStatus.DOCUMENTS_GENERATED,
                                2))))
                .andExpect(status().isOk());

        mockMvc.perform(delete(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                SCENARIO_A,
                                ownerId)
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(1))
                .andExpect(jsonPath("$.details.userId").doesNotExist());

        assertFalse(repository.existsById(scenarioARecord));
        assertFalse(reconciliationRepository.existsById(scenarioARecord));
        assertEquals(SCENARIO_B, repository.findById(scenarioBRecord).orElseThrow().getFixtureScenarioId());
        assertEquals(
                otherOwnerId.toString(),
                repository.findById(otherOwnerRecord).orElseThrow().getUserId());
        assertEquals(ordinary.getId(), repository.findById(ordinary.getId()).orElseThrow().getId());

        mockMvc.perform(delete(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                SCENARIO_A,
                                ownerId)
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(0));
    }

    @Test
    void runtimeOwnerCleanupIsSyntheticBoundedCrossOwnerSafeAndRepeatable()
            throws Exception {
        String identityKey = "claimant-a";
        UUID ownerId = syntheticOwner(SCENARIO_A, identityKey);
        UUID otherOwnerId = syntheticOwner(SCENARIO_A, "claimant-b");
        applicationRecordService.createApplication(
                ownerId.toString(), manualSavedApplication(ownerId, "owner-job"));
        applicationRecordService.createApplication(
                otherOwnerId.toString(),
                manualSavedApplication(otherOwnerId, "other-owner-job"));

        String path = "/internal/system-data/v1/runtime-owners/{scenarioId}"
                + "/identities/{identityKey}/owners/{userId}";
        mockMvc.perform(get(path, SCENARIO_A, identityKey, ownerId)
                        .header(
                                ApplicationServiceIdentityFilter
                                        .ENVIRONMENT_DATA_HEADER,
                                TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.applications").value(1))
                .andExpect(jsonPath("$.details.reconciliations").value(1))
                .andExpect(jsonPath("$.details.events").value(1));

        mockMvc.perform(delete(
                                path,
                                SCENARIO_A,
                                identityKey,
                                UUID.randomUUID())
                        .header(
                                ApplicationServiceIdentityFilter
                                        .ENVIRONMENT_DATA_HEADER,
                                TOKEN))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete(path, SCENARIO_A, identityKey, ownerId)
                        .header(
                                ApplicationServiceIdentityFilter
                                        .ENVIRONMENT_DATA_HEADER,
                                TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.applications").value(1))
                .andExpect(jsonPath("$.details.reconciliations").value(1))
                .andExpect(jsonPath("$.details.events").value(1));

        assertEquals(0, repository.findByUserId(ownerId.toString()).size());
        assertEquals(1, repository.findByUserId(otherOwnerId.toString()).size());

        mockMvc.perform(delete(path, SCENARIO_A, identityKey, ownerId)
                        .header(
                                ApplicationServiceIdentityFilter
                                        .ENVIRONMENT_DATA_HEADER,
                                TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.applications").value(0))
                .andExpect(jsonPath("$.details.commands").value(0))
                .andExpect(jsonPath("$.details.workflows").value(0))
                .andExpect(jsonPath("$.details.reconciliations").value(0))
                .andExpect(jsonPath("$.details.events").value(0))
                .andExpect(jsonPath(
                        "$.details.documentAvailabilityProjections").value(0))
                .andExpect(jsonPath("$.recordsAffected").value(0));
    }

    @Test
    void emptyVersionedSeedClearsOnlyItsScenario() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID scenarioARecord = UUID.randomUUID();
        UUID scenarioBRecord = UUID.randomUUID();
        seed(request(
                        ownerId,
                        SCENARIO_A,
                        List.of(fixture(
                                scenarioARecord,
                                ApplicationStatus.DOCUMENTS_GENERATED,
                                4))))
                .andExpect(status().isOk());
        seed(request(
                        ownerId,
                        SCENARIO_B,
                        List.of(fixture(
                                scenarioBRecord,
                                ApplicationStatus.DOCUMENTS_GENERATED,
                                2))))
                .andExpect(status().isOk());

        seed(request(ownerId, SCENARIO_A, List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsAffected").value(0));

        assertFalse(repository.existsById(scenarioARecord));
        assertFalse(reconciliationRepository.existsById(scenarioARecord));
        assertEquals(SCENARIO_B, repository.findById(scenarioBRecord).orElseThrow().getFixtureScenarioId());
    }

    @Test
    void invalidOrCollidingFixturesFailWithoutCrossingRecordBoundaries() throws Exception {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        UUID identifier = UUID.randomUUID();
        repository.save(ApplicationRecord.builder()
                .id(identifier)
                .userId(firstOwner.toString())
                .jobId("ordinary-job")
                .jobTitle("Ordinary role")
                .companyName("Ordinary company")
                .cvDocumentId(UUID.randomUUID().toString())
                .coverLetterDocumentId(UUID.randomUUID().toString())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build());

        seed(request(
                        secondOwner,
                        SCENARIO_A,
                        List.of(fixture(identifier, ApplicationStatus.DOCUMENTS_GENERATED, 4))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("A fixture record identifier is already owned by another record boundary."));

        SystemDataApplicationSeedRecord duplicate =
                fixture(UUID.randomUUID(), ApplicationStatus.DOCUMENTS_GENERATED, 4);
        seed(request(secondOwner, SCENARIO_A, List.of(duplicate, duplicate)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Fixture record identifiers must be unique."));

        SystemDataApplicationSeedRecord invalidTimestamps =
                new SystemDataApplicationSeedRecord(
                        UUID.randomUUID(),
                        "job-invalid",
                        "job-invalid",
                        "FIXTURE",
                        "external-invalid",
                        "Invalid role",
                        "Invalid company",
                        "Remote",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        "a".repeat(64),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        "b".repeat(64),
                        ApplicationStatus.APPLIED,
                        LocalDateTime.parse("2026-07-10T12:00:00"),
                        LocalDateTime.parse("2026-07-09T12:00:00"),
                        null);
        seed(request(secondOwner, SCENARIO_A, List.of(invalidTimestamps)))
                .andExpect(status().isBadRequest());

        assertEquals(firstOwner.toString(), repository.findById(identifier).orElseThrow().getUserId());
        assertEquals(1, repository.count());
    }

    @Test
    void legacyEntityGraphAndUnknownPersistenceFieldsAreRejected() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String legacyArray = objectMapper.writeValueAsString(List.of(
                ordinaryApplication(ownerId)));
        mockMvc.perform(post("/internal/system-data/v1/application-scenarios")
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyArray))
                .andExpect(status().isBadRequest());

        String unsupportedField = """
                {
                  "schemaVersion": "2.0.0",
                  "scenarioId": "demo-ready-v1",
                  "userId": "%s",
                  "applications": [{
                    "id": "%s",
                    "userId": "%s",
                    "jobId": "job-1",
                    "jobTitle": "Developer",
                    "companyName": "Example",
                    "cvDocumentId": "%s",
                    "coverLetterDocumentId": "%s",
                    "status": "DOCUMENTS_GENERATED",
                    "createdAt": "2026-07-01T09:00:00",
                    "updatedAt": "2026-07-01T09:00:00"
                  }]
                }
                """.formatted(
                ownerId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
        mockMvc.perform(post("/internal/system-data/v1/application-scenarios")
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsupportedField))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request payload or path is invalid."));

        assertEquals(0, repository.count());
    }

    @Test
    void seededProgressedApplicationRetainsDocumentEvidenceAcrossLifecycleUpdate()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        SystemDataApplicationSeedRecord applied =
                fixture(UUID.randomUUID(), ApplicationStatus.APPLIED, 3);
        seed(request(ownerId, SCENARIO_A, List.of(applied)))
                .andExpect(status().isOk());

        ApplicationRecord seeded = repository.findById(applied.id()).orElseThrow();
        ApplicationRecordResponse progressed = applicationRecordService.updateStatus(
                ownerId.toString(),
                applied.id(),
                UpdateStatusRequest.builder()
                        .status("INTERVIEW")
                        .expectedVersion(seeded.getVersion())
                        .build());

        assertEquals(ApplicationStatus.INTERVIEW, progressed.getStatus());
        assertEquals(
                applied.cvDocumentId(),
                progressed.getApplicationUsedCvDocumentReference().getDocumentId());
        assertEquals(
                applied.coverLetterDocumentId(),
                progressed.getApplicationUsedCoverLetterDocumentReference().getDocumentId());
        assertEquals(
                applied.cvDocumentContentSha256(),
                progressed.getApplicationUsedCvDocumentReference().getContentSha256());
    }

    private org.springframework.test.web.servlet.ResultActions seed(
            SystemDataApplicationSeedRequest request) throws Exception {
        return mockMvc.perform(post("/internal/system-data/v1/application-scenarios")
                .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private SystemDataApplicationSeedRequest request(
            UUID ownerId,
            String scenarioId,
            List<SystemDataApplicationSeedRecord> records) {
        return new SystemDataApplicationSeedRequest("2.0.0", scenarioId, ownerId, records);
    }

    private SystemDataApplicationSeedRecord fixture(
            UUID id,
            ApplicationStatus status,
            int createdDaysAgo) {
        LocalDateTime createdAt =
                LocalDateTime.parse("2026-07-10T12:00:00").minusDays(createdDaysAgo);
        LocalDateTime updatedAt = createdAt.plusDays(2);
        LocalDateTime appliedAt =
                status == ApplicationStatus.DOCUMENTS_GENERATED ? null : createdAt.plusDays(1);
        return new SystemDataApplicationSeedRecord(
                id,
                "fixture-job-" + id,
                "fixture-job-" + id,
                "FIXTURE",
                "external-" + id,
                "Software Developer",
                "Example Ltd",
                "Remote",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "a".repeat(64),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "b".repeat(64),
                status,
                createdAt,
                updatedAt,
                appliedAt);
    }

    private ApplicationRecord ordinaryApplication(UUID ownerId) {
        return ApplicationRecord.builder()
                .userId(ownerId.toString())
                .jobId("ordinary-job")
                .jobTitle("Ordinary role")
                .companyName("Ordinary company")
                .cvDocumentId(UUID.randomUUID().toString())
                .coverLetterDocumentId(UUID.randomUUID().toString())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build();
    }

    private CreateApplicationRequest manualSavedApplication(
            UUID ownerId, String jobId) {
        return CreateApplicationRequest.builder()
                .userId(ownerId.toString())
                .jobId(jobId)
                .canonicalJobId(jobId)
                .provider("MANUAL")
                .externalJobId(jobId)
                .jobTitle("Saved role")
                .companyName("Example Ltd")
                .provenance(ApplicationProvenance.MANUAL)
                .initialStatus(ApplicationStatus.SAVED)
                .build();
    }

    private UUID syntheticOwner(String scenarioId, String identityKey) {
        return UUID.nameUUIDFromBytes(("job-seeker-copilot:system-data:"
                + scenarioId
                + ":"
                + identityKey
                + ":user").getBytes(StandardCharsets.UTF_8));
    }
}
