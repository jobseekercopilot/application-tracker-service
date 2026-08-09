package com.jobseekercopilot.applicationtracker.entity;

import com.jobseekercopilot.applicationtracker.dto.DocumentEvidenceProvenance;
import com.jobseekercopilot.applicationtracker.dto.DocumentGroundingState;
import com.jobseekercopilot.applicationtracker.dto.DocumentSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.UUID;

@Entity
@Table(name = "application_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationRecord {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String userId;

    @Column(length = 64)
    private String fixtureScenarioId;

    @Column(nullable = false)
    private String jobId;

    private String canonicalJobId;

    private String provider;

    private String externalJobId;

    @Column(length = 2048)
    private String listingUrl;

    @Column(length = 2048)
    private String applyUrl;

    @Column(length = 255)
    private String attributionLabel;

    @Column(length = 2048)
    private String attributionSourceUrl;

    @Column(length = 2048)
    private String licenceUrl;

    @Column(length = 1000)
    private String disclaimer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ApplicationProvenance provenance = ApplicationProvenance.GENERATED;

    @Column(length = 128)
    private String idempotencyKey;

    @Column(length = 64)
    private String createRequestFingerprint;

    @Column(nullable = false, length = 300)
    private String jobTitle;

    @Column(nullable = false, length = 300)
    private String companyName;

    @Column(length = 300)
    private String location;

    private String cvDocumentId;

    private String cvDocumentFamilyId;

    private Integer cvDocumentVersion;

    @Column(length = 64)
    private String cvDocumentContentSha256;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DocumentSourceType cvDocumentSourceType;

    @Column(length = 64)
    private String cvDocumentOriginalContentSha256;

    private LocalDateTime cvDocumentSelectedAt;

    @Convert(converter = DocumentEvidenceProvenanceConverter.class)
    @Column(columnDefinition = "TEXT")
    private DocumentEvidenceProvenance cvDocumentEvidenceProvenance;

    @Enumerated(EnumType.STRING)
    @Column(length = 48)
    private DocumentGroundingState cvDocumentGroundingState;

    private String coverLetterDocumentId;

    private String coverLetterDocumentFamilyId;

    private Integer coverLetterDocumentVersion;

    @Column(length = 64)
    private String coverLetterDocumentContentSha256;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DocumentSourceType coverLetterDocumentSourceType;

    @Column(length = 64)
    private String coverLetterDocumentOriginalContentSha256;

    private LocalDateTime coverLetterDocumentSelectedAt;

    @Convert(converter = DocumentEvidenceProvenanceConverter.class)
    @Column(columnDefinition = "TEXT")
    private DocumentEvidenceProvenance coverLetterDocumentEvidenceProvenance;

    @Enumerated(EnumType.STRING)
    @Column(length = 48)
    private DocumentGroundingState coverLetterDocumentGroundingState;

    private String applicationUsedCvDocumentId;

    private String applicationUsedCvDocumentFamilyId;

    private Integer applicationUsedCvDocumentVersion;

    @Column(length = 64)
    private String applicationUsedCvDocumentContentSha256;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DocumentSourceType applicationUsedCvDocumentSourceType;

    @Column(length = 64)
    private String applicationUsedCvDocumentOriginalContentSha256;

    private LocalDateTime applicationUsedCvDocumentSelectedAt;

    @Convert(converter = DocumentEvidenceProvenanceConverter.class)
    @Column(columnDefinition = "TEXT")
    private DocumentEvidenceProvenance applicationUsedCvEvidenceProvenance;

    @Enumerated(EnumType.STRING)
    @Column(length = 48)
    private DocumentGroundingState applicationUsedCvGroundingState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private FrozenDocumentSelectionState applicationUsedCvState =
            FrozenDocumentSelectionState.UNKNOWN;

    private String applicationUsedCoverLetterDocumentId;

    private String applicationUsedCoverLetterDocumentFamilyId;

    private Integer applicationUsedCoverLetterDocumentVersion;

    @Column(length = 64)
    private String applicationUsedCoverLetterDocumentContentSha256;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DocumentSourceType applicationUsedCoverLetterDocumentSourceType;

    @Column(length = 64)
    private String applicationUsedCoverLetterDocumentOriginalContentSha256;

    private LocalDateTime applicationUsedCoverLetterDocumentSelectedAt;

    @Convert(converter = DocumentEvidenceProvenanceConverter.class)
    @Column(columnDefinition = "TEXT")
    private DocumentEvidenceProvenance
            applicationUsedCoverLetterEvidenceProvenance;

    @Enumerated(EnumType.STRING)
    @Column(length = 48)
    private DocumentGroundingState applicationUsedCoverLetterGroundingState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private FrozenDocumentSelectionState applicationUsedCoverLetterState =
            FrozenDocumentSelectionState.UNKNOWN;

    private LocalDateTime applicationUsedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime appliedAt;

    private UUID activeDocumentWorkflowId;

    @Version
    @Column(name = "record_version", nullable = false)
    private long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = ApplicationStatus.DOCUMENTS_GENERATED;
        }
        if (provenance == null) {
            provenance = ApplicationProvenance.GENERATED;
        }
        if (status == ApplicationStatus.APPLIED && appliedAt == null) {
            appliedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(Clock.systemUTC());
        if (status == ApplicationStatus.APPLIED && appliedAt == null) {
            appliedAt = LocalDateTime.now(Clock.systemUTC());
        }
    }
}
