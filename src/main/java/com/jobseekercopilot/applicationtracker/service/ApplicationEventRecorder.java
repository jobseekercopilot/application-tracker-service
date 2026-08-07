package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.entity.ApplicationEvent;
import com.jobseekercopilot.applicationtracker.entity.ApplicationEventType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationEventRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApplicationEventRecorder {

    private final EntityManager entityManager;
    private final ApplicationEventRepository repository;

    public ApplicationEvent recordCreated(
            ApplicationRecord record, ApplicationCommandActor actor) {
        return persist(
                record,
                ApplicationEventType.APPLICATION_CREATED,
                null,
                record.getStatus(),
                toInstant(record.getCreatedAt()),
                actor,
                "Application created with " + record.getProvenance() + " provenance.",
                record.getVersion());
    }

    public ApplicationEvent recordStatusChanged(
            ApplicationRecord record,
            ApplicationStatus previousStatus,
            Instant occurredAt,
            ApplicationCommandActor actor,
            String reason) {
        return persist(
                record,
                ApplicationEventType.STATUS_CHANGED,
                previousStatus,
                record.getStatus(),
                occurredAt,
                actor,
                reason,
                record.getVersion());
    }

    public ApplicationEvent recordDocumentReferenceChanged(
            ApplicationRecord record,
            Instant occurredAt,
            ApplicationCommandActor actor,
            String reason) {
        return persist(
                record,
                ApplicationEventType.DOCUMENT_REFERENCE_CHANGED,
                record.getStatus(),
                record.getStatus(),
                occurredAt,
                actor,
                reason,
                record.getVersion());
    }

    public ApplicationEvent recordDocumentReferencesReconciled(
            ApplicationRecord record,
            Instant occurredAt,
            ApplicationCommandActor actor,
            String reason) {
        return persist(
                record,
                ApplicationEventType.DOCUMENT_REFERENCES_RECONCILED,
                record.getStatus(),
                record.getStatus(),
                occurredAt,
                actor,
                reason,
                record.getVersion());
    }

    public ApplicationEvent recordApplicationDocumentsFrozen(
            ApplicationRecord record,
            ApplicationStatus previousStatus,
            Instant occurredAt,
            ApplicationCommandActor actor,
            String reason) {
        return persist(
                record,
                ApplicationEventType.APPLICATION_DOCUMENTS_FROZEN,
                previousStatus,
                ApplicationStatus.APPLIED,
                occurredAt,
                actor,
                reason,
                record.getVersion());
    }

    public ApplicationEvent recordGeneratedWithdrawal(
            ApplicationRecord record,
            Instant occurredAt,
            ApplicationCommandActor actor) {
        return persist(
                record,
                ApplicationEventType.GENERATED_APPLICATION_WITHDRAWN,
                record.getStatus(),
                record.getStatus(),
                occurredAt,
                actor,
                "Generated-only application withdrawn.",
                record.getVersion() + 1);
    }

    public ApplicationEvent recordDeletion(
            ApplicationRecord record,
            Instant occurredAt,
            ApplicationCommandActor actor) {
        return persist(
                record,
                ApplicationEventType.APPLICATION_DELETED,
                record.getStatus(),
                record.getStatus(),
                occurredAt,
                actor,
                "Application deletion accepted by the current retention boundary.",
                record.getVersion() + 1);
    }

    private ApplicationEvent persist(
            ApplicationRecord record,
            ApplicationEventType eventType,
            ApplicationStatus fromStatus,
            ApplicationStatus toStatus,
            Instant occurredAt,
            ApplicationCommandActor actor,
            String reason,
            long recordVersion) {
        if (eventType != ApplicationEventType.APPLICATION_CREATED) {
            repository
                    .findFirstByApplicationIdAndUserIdOrderByRecordVersionDescRecordedAtDesc(
                            record.getId(), record.getUserId())
                    .filter(previous -> occurredAt.isBefore(previous.getOccurredAt()))
                    .ifPresent(previous -> {
                        throw new InvalidRequestException(
                                "occurredAt cannot be before the latest recorded application event.");
                    });
        }
        ApplicationEvent event = ApplicationEvent.builder()
                .applicationId(record.getId())
                .userId(record.getUserId())
                .fixtureScenarioId(record.getFixtureScenarioId())
                .eventType(eventType)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actorType(actor.actorType())
                .actorId(actor.actorId())
                .source(actor.source())
                .occurredAt(occurredAt)
                .recordedAt(Instant.now())
                .reason(normalizeReason(reason))
                .recordVersion(recordVersion)
                .build();
        entityManager.persist(event);
        entityManager.flush();
        return event;
    }

    private Instant toInstant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("Event reason must be at most 500 characters");
        }
        return normalized;
    }
}
