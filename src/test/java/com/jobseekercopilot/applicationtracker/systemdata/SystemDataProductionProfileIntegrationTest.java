package com.jobseekercopilot.applicationtracker.systemdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.security.ApplicationServiceIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "environment-data.enabled=true",
        "environment-data.allowed-environments=production"
})
@AutoConfigureMockMvc
@ActiveProfiles("production")
class SystemDataProductionProfileIntegrationTest {
    private static final String TOKEN = "test-only-environment-data-token-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void productionProfileForbidsEverySystemDataApplicationOperation() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String scenarioId = "production-denial-v1";
        SystemDataApplicationSeedRecord record = new SystemDataApplicationSeedRecord(
                UUID.randomUUID(),
                "job-1",
                "job-1",
                "FIXTURE",
                "external-1",
                "Developer",
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
                ApplicationStatus.DOCUMENTS_GENERATED,
                LocalDateTime.parse("2026-07-01T09:00:00"),
                LocalDateTime.parse("2026-07-01T09:00:00"),
                null);
        SystemDataApplicationSeedRequest request =
                new SystemDataApplicationSeedRequest(
                        "2.0.0",
                        scenarioId,
                        ownerId,
                        List.of(record));

        mockMvc.perform(post("/internal/system-data/v1/application-scenarios")
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Environment data management is disabled for this runtime profile"));

        mockMvc.perform(get(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                scenarioId,
                                ownerId)
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete(
                                "/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}",
                                scenarioId,
                                ownerId)
                        .header(ApplicationServiceIdentityFilter.ENVIRONMENT_DATA_HEADER, TOKEN))
                .andExpect(status().isForbidden());
    }
}
