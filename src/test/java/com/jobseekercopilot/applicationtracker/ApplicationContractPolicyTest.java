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

        assertEquals("2.1.0", contract.at("/info/version").asText());
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
                "updatedAt",
                "version")));
    }

    @Test
    void statusMutationContractPublishesConcurrencyAndConflictSemantics()
            throws Exception {
        JsonNode contract = objectMapper.readTree(CONTRACT.toFile());
        JsonNode operation =
                contract.at("/paths/~1api~1v1~1applications~1{id}~1status/patch");

        assertEquals(
                "#/components/schemas/UpdateStatusRequest",
                operation.at("/requestBody/content/application~1json/schema/$ref").asText());
        assertEquals(
                "#/components/schemas/ApplicationRecordResponse",
                operation.at("/responses/200/content/application~1json/schema/$ref").asText());
        assertEquals(
                "#/components/schemas/ErrorResponse",
                operation.at("/responses/409/content/application~1json/schema/$ref").asText());
        assertEquals(
                0,
                contract.at("/components/schemas/UpdateStatusRequest/properties/expectedVersion/minimum")
                        .asInt());
        assertTrue(contract
                .at("/components/schemas/UpdateStatusRequest/properties/expectedVersion")
                .has("minimum"));
        assertTrue(contract
                .at("/components/schemas/UpdateStatusRequest/properties/expectedVersion/description")
                .asText()
                .contains("409"));
    }
}
