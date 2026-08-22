package com.jobseekercopilot.applicationtracker.dto;

import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
@Schema(description = "Owner-scoped ordered page of immutable application events")
public record ApplicationHistoryResponse(
        UUID applicationId,
        ApplicationStatus currentStatus,
        long currentVersion,
        boolean reconciled,
        List<ApplicationEventResponse> events,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
