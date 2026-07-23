package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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

    @NotBlank(message = "cvDocumentId is required")
    @Schema(description = "ID of the generated CV document (reference only, content not stored here)", example = "cv-123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cvDocumentId;

    @NotBlank(message = "coverLetterDocumentId is required")
    @Schema(description = "ID of the generated cover letter document (reference only, content not stored here)", example = "cl-456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String coverLetterDocumentId;
}
