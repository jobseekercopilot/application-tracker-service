package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to update the status of an application")
public class UpdateStatusRequest {

    @NotBlank(message = "status is required")
    @Schema(
            description = "New status value",
            example = "APPLIED",
            allowableValues = {
                    "DOCUMENTS_GENERATED",
                    "APPLIED",
                    "INTERVIEW",
                    "UNSUCCESSFUL",
                    "OFFER",
                    "ACCEPTED",
                    "REJECTED_BY_USER",
                    "WITHDRAWN"
            },
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String status;

    @PositiveOrZero(message = "expectedVersion must be zero or greater")
    @Schema(
            description = "Optional record version last observed by the caller; stale values return HTTP 409",
            example = "3",
            minimum = "0"
    )
    private Long expectedVersion;

    @Schema(
            description = "UTC time when the lifecycle milestone actually occurred; defaults to receipt time",
            example = "2026-07-26T10:15:30Z")
    private Instant occurredAt;

    @Size(max = 500, message = "reason must be at most 500 characters")
    @Schema(
            description = "Optional concise reason or outcome evidence for the transition",
            example = "First-stage interview confirmed")
    private String reason;
}
