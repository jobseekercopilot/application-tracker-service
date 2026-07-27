package com.jobseekercopilot.applicationtracker.dto;

import com.jobseekercopilot.applicationtracker.entity.DocumentReferenceReconciliationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
@Schema(description = "Durable application/document-reference reconciliation state")
public record DocumentReferenceReconciliationResponse(
        UUID applicationId,
        DocumentReferenceReconciliationStatus status,
        List<String> issueCodes,
        LocalDateTime checkedAt,
        LocalDateTime lastHealthyAt,
        LocalDateTime lastRepairedAt,
        int attemptCount,
        int repairCount,
        Long applicationRecordVersion) {
}
