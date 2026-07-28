package com.jobseekercopilot.applicationtracker.dto;

import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response containing application record details")
public class ApplicationRecordResponse {

    @Schema(
            description = "Unique identifier of the application record",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;

    @Schema(
            description = "ID of the user who owns this application",
            example = "user-123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(
            description = "ID of the job being applied to",
            example = "job-456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String jobId;

    @Schema(
            description = "Stable cross-provider canonical job identifier",
            example = "job_abc123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String canonicalJobId;

    @Schema(
            description = "Job provider name",
            example = "REED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String provider;

    @Schema(
            description = "Provider-specific job identifier",
            example = "123456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String externalJobId;

    @Schema(description = "Authoritative provider listing URL")
    private String listingUrl;

    @Schema(description = "Official provider application URL when distinct from the listing URL")
    private String applyUrl;

    @Schema(description = "Provider attribution text")
    private String attributionLabel;

    @Schema(description = "Authoritative provider attribution URL")
    private String attributionSourceUrl;

    @Schema(description = "Licence URL governing the provider data")
    private String licenceUrl;

    @Schema(description = "Required provider no-endorsement statement")
    private String disclaimer;

    @Schema(
            description = "How the application entered the tracker",
            example = "MANUAL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ApplicationProvenance provenance;

    @Schema(
            description = "Title of the job",
            example = "Java Developer",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String jobTitle;

    @Schema(
            description = "Name of the company",
            example = "Example Ltd",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String companyName;

    @Schema(description = "Job location", example = "Dorking")
    private String location;

    @Schema(
            description = "ID of the generated CV document (reference only)",
            example = "cv-123")
    private String cvDocumentId;

    @Schema(
            description = "ID of the generated cover letter document (reference only)",
            example = "cl-456")
    private String coverLetterDocumentId;

    @Schema(description = "Current approved CV selected before application submission")
    private DocumentVersionReference cvDocumentReference;

    @Schema(description = "Current approved cover letter selected before application submission")
    private DocumentVersionReference coverLetterDocumentReference;

    @Schema(description = "Frozen CV version used when this application first progressed")
    private DocumentVersionReference applicationUsedCvDocumentReference;

    @Schema(description = "Frozen cover-letter version used when this application first progressed")
    private DocumentVersionReference applicationUsedCoverLetterDocumentReference;

    @Schema(description = "Timestamp when application-used references were frozen")
    private LocalDateTime applicationUsedAt;

    @Schema(
            description = "Current application status",
            example = "DOCUMENTS_GENERATED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ApplicationStatus status;

    @Schema(
            description = "Timestamp when the record was created",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    @Schema(
            description = "Timestamp when the record was last updated",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime updatedAt;

    @Schema(description = "Timestamp when the application was submitted (null if not yet applied)")
    private LocalDateTime appliedAt;

    @Schema(
            description = "Monotonic record version for optimistic concurrency",
            example = "3",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private long version;
}
