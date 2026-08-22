package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "Stale application command response with the authoritative current record")
public record ApplicationVersionConflictResponse(
        int status,
        String message,
        LocalDateTime timestamp,
        ApplicationRecordResponse currentApplication) {
}
