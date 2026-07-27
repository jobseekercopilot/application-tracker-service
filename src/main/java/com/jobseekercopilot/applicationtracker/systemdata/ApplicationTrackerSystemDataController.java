package com.jobseekercopilot.applicationtracker.systemdata;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(
        value = "/internal/system-data",
        produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "environmentDataToken")
@Tag(name = "System Data Applications")
@Validated
public class ApplicationTrackerSystemDataController {
    private final EnvironmentDataGuard guard;
    private final SystemDataApplicationService systemDataService;

    public ApplicationTrackerSystemDataController(
            EnvironmentDataGuard guard,
            SystemDataApplicationService systemDataService) {
        this.guard = guard;
        this.systemDataService = systemDataService;
    }

    @PostMapping(
            value = "/v1/application-scenarios",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SystemDataResult> seedApplications(
            @Valid @RequestBody SystemDataApplicationSeedRequest request) {
        guard.requireEnabled();
        int count = systemDataService.seed(request);
        return ResponseEntity.ok(SystemDataResult.success(
                "SEED",
                count,
                guard.activeEnvironment(),
                Map.of(
                        "schemaVersion", request.schemaVersion(),
                        "scenarioId", request.scenarioId(),
                        "applications", count)));
    }

    @DeleteMapping("/v1/application-scenarios/{scenarioId}/owners/{userId}")
    public ResponseEntity<SystemDataResult> resetApplications(
            @PathVariable
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,54}-v[1-9][0-9]{0,6}")
            String scenarioId,
            @PathVariable UUID userId) {
        guard.requireEnabled();
        int count = systemDataService.reset(userId, scenarioId);
        return ResponseEntity.ok(SystemDataResult.success(
                "RESET",
                count,
                guard.activeEnvironment(),
                Map.of("scenarioId", scenarioId, "applications", count)));
    }

    @GetMapping("/v1/application-scenarios/{scenarioId}/owners/{userId}")
    public ResponseEntity<SystemDataResult> verifyApplications(
            @PathVariable
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,54}-v[1-9][0-9]{0,6}")
            String scenarioId,
            @PathVariable UUID userId) {
        guard.requireEnabled();
        SystemDataApplicationService.ScenarioApplicationSummary summary =
                systemDataService.verify(userId, scenarioId);
        return ResponseEntity.ok(SystemDataResult.success(
                "VERIFY",
                summary.count(),
                guard.activeEnvironment(),
                Map.of(
                        "scenarioId", scenarioId,
                        "applications", summary.count(),
                        "byStatus", summary.byStatus())));
    }
}
