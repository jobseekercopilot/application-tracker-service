package com.jobseekercopilot.applicationtracker.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record UpdateDocumentAvailabilityRequest(
        @NotNull DocumentAvailabilityState availability,
        String unavailableReason,
        @NotNull LocalDateTime occurredAt) {
}
