package com.jobseekercopilot.applicationtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "application_document_workflows")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationDocumentWorkflow {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationDocumentWorkflowType workflowType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ApplicationDocumentWorkflowStatus status =
            ApplicationDocumentWorkflowStatus.PENDING;

    private String cvDocumentId;

    private String coverLetterDocumentId;

    @Column(nullable = false)
    private boolean cvCleanupRequired;

    @Column(nullable = false)
    private boolean coverLetterCleanupRequired;

    @Column(length = 32)
    private String documentType;

    private String sourceDocumentId;

    private String replacementDocumentId;

    @Column(length = 64)
    private String requestSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApplicationActorType actorType;

    @Column(nullable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ApplicationEventSource source;

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean retryable = true;

    @Column(length = 64)
    private String lastErrorCode;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Version
    @Column(name = "workflow_version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(Clock.systemUTC());
    }
}
