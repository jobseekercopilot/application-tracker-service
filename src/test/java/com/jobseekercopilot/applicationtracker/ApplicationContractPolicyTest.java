package com.jobseekercopilot.applicationtracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ApplicationContractPolicyTest {

    private static final Path CONTRACT = Path.of("contracts/openapi.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ownerScopedListContractIsVersionedAndExplicit() throws Exception {
        JsonNode contract = objectMapper.readTree(CONTRACT.toFile());
        JsonNode operation =
                contract.at("/paths/~1api~1v1~1applications~1user~1{userId}/get");

        assertEquals("2.0.0", contract.at("/info/version").asText());
        assertEquals("getApplicationsForUser", operation.path("operationId").asText());
        assertEquals(
                        Set.of("bearerAuth", "serviceToken"),
                StreamSupport.stream(operation.path("security").spliterator(), false)
                        .flatMap(requirement ->
                                StreamSupport.stream(
                                        Spliterators.spliteratorUnknownSize(
                                                requirement.fieldNames(),
                                                0),
                                        false))
                        .collect(Collectors.toSet()));

        JsonNode responses = operation.path("responses");
        assertEquals(
                "#/components/schemas/ApplicationRecordResponse",
                responses.at("/200/content/application~1json/schema/items/$ref").asText());
        assertEquals(
                "#/components/schemas/SecurityErrorResponse",
                responses.at("/401/content/application~1json/schema/$ref").asText());
        assertEquals(
                "#/components/schemas/SecurityErrorResponse",
                responses.at("/403/content/application~1json/schema/$ref").asText());
        assertEquals(
                "#/components/schemas/ErrorResponse",
                responses.at("/404/content/application~1json/schema/$ref").asText());

        Set<String> requiredFields = StreamSupport.stream(
                        contract.at("/components/schemas/ApplicationRecordResponse/required")
                                .spliterator(),
                        false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
        assertTrue(requiredFields.containsAll(Set.of(
                "id",
                "userId",
                "jobId",
                "canonicalJobId",
                "provider",
                "externalJobId",
                "jobTitle",
                "companyName",
                "cvDocumentId",
                "coverLetterDocumentId",
                "status",
                "createdAt",
                "updatedAt")));
    }

    @Test
    void systemDataContractIsVersionedAndScenarioScoped() throws Exception {
        JsonNode contract = objectMapper.readTree(CONTRACT.toFile());

        assertTrue(contract.at("/paths/~1internal~1system-data~1seed~1applications").isMissingNode());
        assertTrue(contract.at("/paths/~1internal~1system-data~1verify~1applications~1{userId}").isMissingNode());
        assertTrue(contract.at("/paths/~1internal~1system-data~1scenario~1{scenarioId}~1applications~1{userId}")
                .isMissingNode());

        JsonNode seed = contract.at(
                "/paths/~1internal~1system-data~1v1~1application-scenarios/post");
        assertEquals(
                "#/components/schemas/SystemDataApplicationSeedRequest",
                seed.at("/requestBody/content/application~1json/schema/$ref").asText());
        assertEquals(
                "#/components/schemas/SystemDataResult",
                seed.at("/responses/200/content/application~1json/schema/$ref").asText());

        JsonNode scoped = contract.at(
                "/paths/~1internal~1system-data~1v1~1application-scenarios~1{scenarioId}~1owners~1{userId}");
        assertTrue(scoped.has("get"));
        assertTrue(scoped.has("delete"));

        JsonNode fixtureRecord =
                contract.at("/components/schemas/SystemDataApplicationSeedRecord/properties");
        assertEquals(
                false,
                contract.at("/components/schemas/SystemDataApplicationSeedRecord/additionalProperties")
                        .asBoolean());
        assertEquals(
                false,
                contract.at("/components/schemas/SystemDataApplicationSeedRequest/additionalProperties")
                        .asBoolean());
        assertTrue(fixtureRecord.has("id"));
        assertTrue(fixtureRecord.has("jobId"));
        assertTrue(fixtureRecord.has("status"));
        assertTrue(fixtureRecord.has("createdAt"));
        assertTrue(fixtureRecord.has("updatedAt"));
        assertTrue(!fixtureRecord.has("userId"));
        assertTrue(!fixtureRecord.has("fixtureScenarioId"));
    }
}
