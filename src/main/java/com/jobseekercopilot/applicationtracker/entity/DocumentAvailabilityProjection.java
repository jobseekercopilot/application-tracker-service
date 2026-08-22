package com.jobseekercopilot.applicationtracker.entity;

import com.jobseekercopilot.applicationtracker.dto.DocumentAvailabilityState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "document_availability_projections",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_document_availability_owner_document",
                columnNames = {"owner_id", "document_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAvailabilityProjection {
    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "owner_id", nullable = false, length = 255)
    private String ownerId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentAvailabilityState availability;

    @Column(length = 64)
    private String unavailableReason;

    private LocalDateTime unavailableAt;

    @Column(nullable = false)
    private LocalDateTime lifecycleOccurredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

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
