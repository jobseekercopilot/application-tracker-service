package com.jobseekercopilot.applicationtracker.exception;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Stable redacted authentication or authorization error")
public record SecurityErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String message) {
}
