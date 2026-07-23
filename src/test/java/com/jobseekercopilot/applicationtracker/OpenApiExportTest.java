package com.jobseekercopilot.applicationtracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiExportTest {

    private static final Path CONTRACT = Path.of("contracts/openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publishedContractMatchesTheRunningApplication() throws Exception {
        String specification = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var generated = objectMapper.readTree(specification);

        assertEquals(
                "Proprietary and confidential",
                generated.at("/info/license/name").asText());
        assertEquals(
                "http://localhost:8088",
                generated.at("/servers/0/url").asText());

        String formattedSpecification = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(generated)
                + System.lineSeparator();

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), formattedSpecification);

        if (Boolean.getBoolean("applicationTracker.updateContract")) {
            Files.writeString(CONTRACT, formattedSpecification);
        } else {
            assertEquals(
                    objectMapper.readTree(Files.readString(CONTRACT)),
                    generated,
                    "Published OpenAPI contract is stale; use the documented update command and review the diff");
        }
    }
}
