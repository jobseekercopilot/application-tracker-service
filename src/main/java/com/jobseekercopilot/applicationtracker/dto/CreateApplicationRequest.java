package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create a new application record")
public class CreateApplicationRequest {

    @NotBlank(message = "userId is required")
    @Schema(description = "ID of the user creating the application", example = "user-123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @NotBlank(message = "jobId is required")
    @Schema(description = "ID of the job being applied to", example = "job-456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String jobId;

    @Schema(description = "Stable cross-provider canonical job identifier", example = "job_abc123")
    private String canonicalJobId;

    @Schema(description = "Job provider name", example = "REED")
    private String provider;

    @Schema(description = "Provider-specific job identifier", example = "123456")
    private String externalJobId;

    @NotBlank(message = "jobTitle is required")
    @Schema(description = "Title of the job", example = "Java Developer", requiredMode = Schema.RequiredMode.REQUIRED)
    private String jobTitle;

    @NotBlank(message = "companyName is required")
    @Schema(description = "Name of the company offering the job", example = "Example Ltd", requiredMode = Schema.RequiredMode.REQUIRED)
    private String companyName;

    @Schema(description = "Job location", example = "Dorking")
    private String location;

    @NotNull(message = "cvDocumentId is required")
    @Schema(description = "Approved CV version ID in Document Store", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID cvDocumentId;

    @NotNull(message = "coverLetterDocumentId is required")
    @Schema(description = "Approved cover-letter version ID in Document Store", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID coverLetterDocumentId;
}
