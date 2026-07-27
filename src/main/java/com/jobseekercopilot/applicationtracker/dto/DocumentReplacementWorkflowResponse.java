package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Durable application document-replacement state")
public class DocumentReplacementWorkflowResponse {

    private UUID operationId;
    private UUID applicationId;
    private String documentType;
    private UUID sourceDocumentId;
    private UUID replacementDocumentId;
    private String operationStatus;
    private boolean retryable;
    private String recoveryCode;
    private LocalDateTime completedAt;
    private String cvDocumentId;
    private String coverLetterDocumentId;
    private String message;
}
