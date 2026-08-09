package com.jobseekercopilot.applicationtracker.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentAvailabilityResponse(
        UUID documentId,
        DocumentAvailabilityState availability,
        String unavailableReason,
        LocalDateTime unavailableAt,
        LocalDateTime updatedAt) {
}
