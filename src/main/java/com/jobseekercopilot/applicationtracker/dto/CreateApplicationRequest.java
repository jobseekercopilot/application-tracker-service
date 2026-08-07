package com.jobseekercopilot.applicationtracker.dto;

import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(max = 255, message = "userId must be at most 255 characters")
    @Schema(description = "ID of the user creating the application", example = "user-123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @NotBlank(message = "jobId is required")
    @Size(max = 255, message = "jobId must be at most 255 characters")
    @Schema(description = "ID of the job being applied to", example = "job-456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String jobId;

    @Schema(description = "Stable cross-provider canonical job identifier", example = "job_abc123")
    @Size(max = 255, message = "canonicalJobId must be at most 255 characters")
    private String canonicalJobId;

    @Schema(description = "Job provider name", example = "REED")
    @Size(max = 64, message = "provider must be at most 64 characters")
    private String provider;

    @Schema(description = "Provider-specific job identifier", example = "123456")
    @Size(max = 255, message = "externalJobId must be at most 255 characters")
    private String externalJobId;

    @NotBlank(message = "jobTitle is required")
    @Size(max = 300, message = "jobTitle must be at most 300 characters")
    @Schema(description = "Title of the job", example = "Java Developer", requiredMode = Schema.RequiredMode.REQUIRED)
    private String jobTitle;

    @NotBlank(message = "companyName is required")
    @Size(max = 300, message = "companyName must be at most 300 characters")
    @Schema(description = "Name of the company offering the job", example = "Example Ltd", requiredMode = Schema.RequiredMode.REQUIRED)
    private String companyName;

    @Schema(description = "Job location", example = "Dorking")
    @Size(max = 300, message = "location must be at most 300 characters")
    private String location;

    @Schema(description = "Approved CV version ID in Document Store; optional for manual/external applications", format = "uuid")
    private UUID cvDocumentId;

    @Schema(description = "Approved cover-letter version ID in Document Store; optional for manual/external applications", format = "uuid")
    private UUID coverLetterDocumentId;

    @Schema(
            description = "How the application entered the tracker. Defaults to GENERATED for backwards-compatible producers.",
            example = "MANUAL")
    private ApplicationProvenance provenance;

    @Schema(
            description = "Initial lifecycle state. GENERATED may start as DOCUMENTS_GENERATED or APPLIED; MANUAL and EXTERNAL default to APPLIED and may explicitly start as SAVED.",
            example = "SAVED")
    private ApplicationStatus initialStatus;
}
