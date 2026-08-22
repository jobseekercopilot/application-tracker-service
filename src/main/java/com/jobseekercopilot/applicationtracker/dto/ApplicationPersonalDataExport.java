package com.jobseekercopilot.applicationtracker.dto;

import java.time.Instant;
import java.util.List;

public record ApplicationPersonalDataExport(
        String schemaVersion,
        Instant generatedAt,
        List<ApplicationRecordResponse> applications,
        List<ApplicationEventResponse> events) {
}
