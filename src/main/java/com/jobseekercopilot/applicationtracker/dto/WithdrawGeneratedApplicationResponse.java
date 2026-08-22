package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response returned when generated documents are withdrawn before applying")
public class WithdrawGeneratedApplicationResponse {

    @Schema(description = "Application record ID that was withdrawn")
    private UUID applicationId;

    @Schema(description = "Status the job should show after withdrawal", example = "NEW")
    private String status;

    @Schema(description = "Whether the generated application was withdrawn", example = "true")
    private boolean withdrawn;

    @Schema(description = "Durable workflow operation ID")
    private UUID operationId;

    @Schema(
            description = "Durable workflow state",
            allowableValues = {
                "PENDING",
                "RUNNING",
                "RECOVERY_REQUIRED",
                "COMPLETED"
            })
    private String operationStatus;

    @Schema(description = "Whether the incomplete workflow can be retried automatically")
    private boolean retryable;

    @Schema(description = "Stable non-sensitive recovery reason when work is incomplete")
    private String recoveryCode;

    @Schema(description = "UTC completion time when document cleanup and withdrawal committed")
    private LocalDateTime completedAt;

    @Schema(description = "Human-readable withdrawal result")
    private String message;
}
