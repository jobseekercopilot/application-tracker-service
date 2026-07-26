package com.jobseekercopilot.applicationtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(nullable = false, length = 300)
    private String jobTitle;

    @Column(nullable = false, length = 300)
    private String companyName;

    @Column(length = 300)
    private String location;

    @Column(nullable = false)
    private String cvDocumentId;

    @Column(nullable = false)
    private String coverLetterDocumentId;

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
        if (fixtureScenarioId == null) {
            updatedAt = LocalDateTime.now();
        }
        if (status == ApplicationStatus.APPLIED && appliedAt == null) {
            appliedAt = LocalDateTime.now();
        }
    }
}
