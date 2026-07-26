package com.jobseekercopilot.applicationtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "application_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String jobId;

    private String canonicalJobId;

    private String provider;

    private String externalJobId;

    @Column(nullable = false)
    private String jobTitle;

    @Column(nullable = false)
    private String companyName;

    private String location;

    @Column(nullable = false)
    private String cvDocumentId;

    private String cvDocumentFamilyId;

    private Integer cvDocumentVersion;

    @Column(length = 64)
    private String cvDocumentContentSha256;

    @Column(nullable = false)
    private String coverLetterDocumentId;

    private String coverLetterDocumentFamilyId;

    private Integer coverLetterDocumentVersion;

    @Column(length = 64)
    private String coverLetterDocumentContentSha256;

    private String applicationUsedCvDocumentId;

    private String applicationUsedCvDocumentFamilyId;

    private Integer applicationUsedCvDocumentVersion;

    @Column(length = 64)
    private String applicationUsedCvDocumentContentSha256;

    private String applicationUsedCoverLetterDocumentId;

    private String applicationUsedCoverLetterDocumentFamilyId;

    private Integer applicationUsedCoverLetterDocumentVersion;

    @Column(length = 64)
    private String applicationUsedCoverLetterDocumentContentSha256;

    private LocalDateTime applicationUsedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime appliedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = ApplicationStatus.DOCUMENTS_GENERATED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (status == ApplicationStatus.APPLIED && appliedAt == null) {
            appliedAt = LocalDateTime.now();
        }
    }
}
