package com.jobseekercopilot.applicationtracker.systemdata;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/system-data")
@SecurityRequirement(name = "environmentDataToken")
public class ApplicationTrackerSystemDataController {
    private final EnvironmentDataGuard guard;
    private final ApplicationRecordRepository repository;

    public ApplicationTrackerSystemDataController(EnvironmentDataGuard guard, ApplicationRecordRepository repository) {
        this.guard = guard;
        this.repository = repository;
    }

    @PostMapping("/seed/applications")
    public ResponseEntity<SystemDataResult> seedApplications(@RequestBody List<ApplicationRecord> records) {
        guard.requireEnabled();
        List<ApplicationRecord> saved = repository.saveAll(records);
        return ResponseEntity.ok(SystemDataResult.success("SEED", saved.size(), guard.activeEnvironment(), Map.of(
                "applications", saved.size())));
    }

    @Transactional
    @DeleteMapping("/scenario/{scenarioId}/applications/{userId}")
    public ResponseEntity<SystemDataResult> resetApplications(@PathVariable String scenarioId, @PathVariable String userId) {
        guard.requireEnabled();
        int count = repository.findByUserId(userId).size();
        repository.deleteByUserId(userId);
        return ResponseEntity.ok(SystemDataResult.success("RESET", count, guard.activeEnvironment(), Map.of(
                "scenarioId", scenarioId,
                "userId", userId)));
    }

    @GetMapping("/verify/applications/{userId}")
    public ResponseEntity<SystemDataResult> verifyApplications(@PathVariable String userId) {
        guard.requireEnabled();
        List<ApplicationRecord> records = repository.findByUserId(userId);
        Map<String, Long> byStatus = records.stream()
                .collect(java.util.stream.Collectors.groupingBy(record -> record.getStatus().name(), java.util.stream.Collectors.counting()));
        return ResponseEntity.ok(SystemDataResult.success("VERIFY", records.size(), guard.activeEnvironment(), Map.of(
                "userId", userId,
                "applications", records.size(),
                "byStatus", byStatus)));
    }
}
