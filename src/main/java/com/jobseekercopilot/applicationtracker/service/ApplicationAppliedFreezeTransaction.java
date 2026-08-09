package com.jobseekercopilot.applicationtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationActorType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationAppliedCommand;
import com.jobseekercopilot.applicationtracker.entity.ApplicationLifecycle;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.FrozenDocumentSelectionState;
import com.jobseekercopilot.applicationtracker.exception.ApplicationCreationConflictException;
import com.jobseekercopilot.applicationtracker.exception.ApplicationSelectionVersionConflictException;
import com.jobseekercopilot.applicationtracker.exception.IdempotencyConflictException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationAppliedCommandRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ApplicationAppliedFreezeTransaction {
    private final ApplicationAppliedCommandRepository commandRepository;
    private final ApplicationRecordRepository applicationRepository;
    private final DocumentReferenceVerifier documentReferenceVerifier;
    private final ApplicationEventRecorder eventRecorder;
    private final ApplicationDocumentReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ApplicationRecordResponse> findReplay(
            String ownerId,
            UUID applicationId,
            String idempotencyKey,
            String fingerprint) {
        return commandRepository
                .findByUserIdAndIdempotencyKey(ownerId, idempotencyKey)
                .map(command -> replay(command, applicationId, fingerprint));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplicationRecordResponse apply(
            String ownerId,
            UUID applicationId,
            String idempotencyKey,
            String fingerprint,
            UpdateStatusRequest request,
            ApplicationCommandActor actor) {
        Optional<ApplicationAppliedCommand> existing = commandRepository
                .findByUserIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), applicationId, fingerprint);
        }
        ApplicationRecord record = applicationRepository
                .findForUpdateByIdAndUserId(applicationId, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        existing = commandRepository
                .findByUserIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), applicationId, fingerprint);
        }
        requireApplicable(record, request, actor);
        ApplicationStatus previousStatus = record.getStatus();
        Instant occurredAt = resolveOccurredAt(record, request.getOccurredAt());
        if (record.getCvDocumentId() != null
                || record.getCoverLetterDocumentId() != null) {
            reconciliationService.requireHealthy(record);
        }
        DocumentVersionReference cv = verify(
                record, record.getCvDocumentId(), DocumentType.CV);
        DocumentVersionReference coverLetter = verify(
                record,
                record.getCoverLetterDocumentId(),
                DocumentType.COVER_LETTER);

        LocalDateTime frozenAt = LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
        freezeCv(record, cv, frozenAt);
        freezeCoverLetter(record, coverLetter, frozenAt);
        record.setApplicationUsedAt(frozenAt);
        record.setStatus(ApplicationStatus.APPLIED);
        record.setAppliedAt(frozenAt);
        ApplicationRecord updated = applicationRepository.saveAndFlush(record);
        eventRecorder.recordApplicationDocumentsFrozen(
                updated,
                previousStatus,
                occurredAt,
                actor,
                "Application document selections frozen: CV "
                        + state(cv)
                        + "; cover letter "
                        + state(coverLetter)
                        + ".");
        eventRecorder.recordStatusChanged(
                updated,
                previousStatus,
                occurredAt,
                actor,
                request.getReason());
        if (cv != null || coverLetter != null) {
            reconciliationService.markHealthy(updated);
        }
        ApplicationRecordResponse outcome = toResponse(updated);
        commandRepository.saveAndFlush(
                ApplicationAppliedCommand.builder()
                        .userId(ownerId)
                        .applicationId(applicationId)
                        .idempotencyKey(idempotencyKey)
                        .requestFingerprint(fingerprint)
                        .outcomeResponseJson(writeOutcome(outcome))
                        .build());
        return outcome;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ApplicationRecordResponse resolveConstraintRace(
            String ownerId,
            UUID applicationId,
            String idempotencyKey,
            String fingerprint) {
        return commandRepository
                .findByUserIdAndIdempotencyKey(ownerId, idempotencyKey)
                .map(command -> replay(command, applicationId, fingerprint))
                .orElseThrow(() -> new ApplicationCreationConflictException(
                        "Apply command conflicted with another request. Retry with the same idempotency key."));
    }

    private void requireApplicable(
            ApplicationRecord record,
            UpdateStatusRequest request,
            ApplicationCommandActor actor) {
        if (actor.actorType() == ApplicationActorType.SERVICE) {
            throw new AccessDeniedException(
                    "Producer status commands cannot apply an application");
        }
        if (record.getActiveDocumentWorkflowId() != null) {
            throw new InvalidStatusException(
                    "Application status cannot change while a document workflow is active.");
        }
        if (record.getVersion() != request.getExpectedVersion()) {
            throw new ApplicationSelectionVersionConflictException(
                    toResponse(record));
        }
        if (record.getStatus() == ApplicationStatus.APPLIED
                || record.getApplicationUsedAt() != null) {
            throw new InvalidStatusException(
                    "Application documents are already frozen.");
        }
        ApplicationLifecycle.requireTransition(
                record.getStatus(), ApplicationStatus.APPLIED);
    }

    private DocumentVersionReference verify(
            ApplicationRecord record,
            String documentId,
            DocumentType documentType) {
        if (documentId == null) {
            return null;
        }
        try {
            return documentReferenceVerifier.verify(
                    record.getUserId(),
                    UUID.fromString(documentId),
                    selectionJobId(record),
                    record.getId(),
                    documentType);
        } catch (IllegalArgumentException invalidIdentifier) {
            throw new InvalidDocumentReferenceException();
        }
    }

    private String selectionJobId(ApplicationRecord record) {
        return record.getCanonicalJobId() == null
                        || record.getCanonicalJobId().isBlank()
                ? record.getJobId()
                : record.getCanonicalJobId();
    }

    private void freezeCv(
            ApplicationRecord record,
            DocumentVersionReference reference,
            LocalDateTime fallbackSelectedAt) {
        record.setApplicationUsedCvState(reference == null
                ? FrozenDocumentSelectionState.OMITTED
                : FrozenDocumentSelectionState.SELECTED);
        record.setApplicationUsedCvDocumentId(value(reference, true));
        record.setApplicationUsedCvDocumentFamilyId(value(reference, false));
        record.setApplicationUsedCvDocumentVersion(
                reference == null ? null : reference.getVersion());
        record.setApplicationUsedCvDocumentContentSha256(
                reference == null ? null : reference.getContentSha256());
        record.setApplicationUsedCvDocumentSourceType(
                reference == null ? null : reference.getSourceType());
        record.setApplicationUsedCvDocumentOriginalContentSha256(
                reference == null
                        ? null
                        : reference.getOriginalContentSha256());
        record.setApplicationUsedCvDocumentSelectedAt(reference == null
                ? null
                : java.util.Objects.requireNonNullElse(
                        record.getCvDocumentSelectedAt(), fallbackSelectedAt));
        record.setApplicationUsedCvEvidenceProvenance(
                reference == null ? null : reference.getEvidenceProvenance());
        record.setApplicationUsedCvGroundingState(
                reference == null ? null : reference.getGroundingState());
    }

    private void freezeCoverLetter(
            ApplicationRecord record,
            DocumentVersionReference reference,
            LocalDateTime fallbackSelectedAt) {
        record.setApplicationUsedCoverLetterState(reference == null
                ? FrozenDocumentSelectionState.OMITTED
                : FrozenDocumentSelectionState.SELECTED);
        record.setApplicationUsedCoverLetterDocumentId(value(reference, true));
        record.setApplicationUsedCoverLetterDocumentFamilyId(value(reference, false));
        record.setApplicationUsedCoverLetterDocumentVersion(
                reference == null ? null : reference.getVersion());
        record.setApplicationUsedCoverLetterDocumentContentSha256(
                reference == null ? null : reference.getContentSha256());
        record.setApplicationUsedCoverLetterDocumentSourceType(
                reference == null ? null : reference.getSourceType());
        record.setApplicationUsedCoverLetterDocumentOriginalContentSha256(
                reference == null
                        ? null
                        : reference.getOriginalContentSha256());
        record.setApplicationUsedCoverLetterDocumentSelectedAt(reference == null
                ? null
                : java.util.Objects.requireNonNullElse(
                        record.getCoverLetterDocumentSelectedAt(),
                        fallbackSelectedAt));
        record.setApplicationUsedCoverLetterEvidenceProvenance(
                reference == null ? null : reference.getEvidenceProvenance());
        record.setApplicationUsedCoverLetterGroundingState(
                reference == null ? null : reference.getGroundingState());
    }

    private String value(DocumentVersionReference reference, boolean documentId) {
        if (reference == null) {
            return null;
        }
        return (documentId
                        ? reference.getDocumentId()
                        : reference.getDocumentFamilyId())
                .toString();
    }

    private String state(DocumentVersionReference reference) {
        return reference == null ? "omitted" : "selected";
    }

    private Instant resolveOccurredAt(
            ApplicationRecord record, Instant requestedOccurredAt) {
        Instant now = Instant.now();
        if (requestedOccurredAt == null) {
            return now;
        }
        if (record.getCreatedAt() != null
                && requestedOccurredAt.isBefore(
                        record.getCreatedAt().toInstant(ZoneOffset.UTC))) {
            throw new InvalidRequestException(
                    "occurredAt cannot be before the application was created.");
        }
        if (requestedOccurredAt.isAfter(now.plusSeconds(300))) {
            throw new InvalidRequestException(
                    "occurredAt cannot be more than five minutes in the future.");
        }
        return requestedOccurredAt;
    }

    private ApplicationRecordResponse replay(
            ApplicationAppliedCommand command,
            UUID applicationId,
            String fingerprint) {
        if (!applicationId.equals(command.getApplicationId())
                || !fingerprint.equals(command.getRequestFingerprint())) {
            throw new IdempotencyConflictException();
        }
        try {
            return objectMapper.readValue(
                    command.getOutcomeResponseJson(),
                    ApplicationRecordResponse.class);
        } catch (JsonProcessingException corruptedLedger) {
            throw new IllegalStateException(
                    "Stored apply-command outcome is unreadable",
                    corruptedLedger);
        }
    }

    private String writeOutcome(ApplicationRecordResponse outcome) {
        try {
            return objectMapper.writeValueAsString(outcome);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException(
                    "Apply-command outcome could not be stored",
                    serializationFailure);
        }
    }

    private ApplicationRecordResponse toResponse(ApplicationRecord record) {
        return ApplicationRecordResponse.builder()
                .id(record.getId())
                .userId(record.getUserId())
                .jobId(record.getJobId())
                .canonicalJobId(record.getCanonicalJobId())
                .provider(record.getProvider())
                .externalJobId(record.getExternalJobId())
                .provenance(record.getProvenance())
                .jobTitle(record.getJobTitle())
                .companyName(record.getCompanyName())
                .location(record.getLocation())
                .cvDocumentId(record.getCvDocumentId())
                .coverLetterDocumentId(record.getCoverLetterDocumentId())
                .cvDocumentReference(reference(
                        record.getCvDocumentId(),
                        record.getCvDocumentFamilyId(),
                        record.getCvDocumentVersion(),
                        record.getCvDocumentContentSha256(),
                        record.getCvDocumentSourceType(),
                        record.getCvDocumentOriginalContentSha256(),
                        record.getCvDocumentSelectedAt(),
                        record.getCvDocumentEvidenceProvenance(),
                        record.getCvDocumentGroundingState(),
                        record,
                        DocumentType.CV))
                .coverLetterDocumentReference(reference(
                        record.getCoverLetterDocumentId(),
                        record.getCoverLetterDocumentFamilyId(),
                        record.getCoverLetterDocumentVersion(),
                        record.getCoverLetterDocumentContentSha256(),
                        record.getCoverLetterDocumentSourceType(),
                        record.getCoverLetterDocumentOriginalContentSha256(),
                        record.getCoverLetterDocumentSelectedAt(),
                        record.getCoverLetterDocumentEvidenceProvenance(),
                        record.getCoverLetterDocumentGroundingState(),
                        record,
                        DocumentType.COVER_LETTER))
                .applicationUsedCvDocumentReference(reference(
                        record.getApplicationUsedCvDocumentId(),
                        record.getApplicationUsedCvDocumentFamilyId(),
                        record.getApplicationUsedCvDocumentVersion(),
                        record.getApplicationUsedCvDocumentContentSha256(),
                        record.getApplicationUsedCvDocumentSourceType(),
                        record.getApplicationUsedCvDocumentOriginalContentSha256(),
                        record.getApplicationUsedCvDocumentSelectedAt(),
                        record.getApplicationUsedCvEvidenceProvenance(),
                        record.getApplicationUsedCvGroundingState(),
                        record,
                        DocumentType.CV))
                .applicationUsedCvState(record.getApplicationUsedCvState())
                .applicationUsedCoverLetterDocumentReference(reference(
                        record.getApplicationUsedCoverLetterDocumentId(),
                        record.getApplicationUsedCoverLetterDocumentFamilyId(),
                        record.getApplicationUsedCoverLetterDocumentVersion(),
                        record.getApplicationUsedCoverLetterDocumentContentSha256(),
                        record.getApplicationUsedCoverLetterDocumentSourceType(),
                        record.getApplicationUsedCoverLetterDocumentOriginalContentSha256(),
                        record.getApplicationUsedCoverLetterDocumentSelectedAt(),
                        record.getApplicationUsedCoverLetterEvidenceProvenance(),
                        record.getApplicationUsedCoverLetterGroundingState(),
                        record,
                        DocumentType.COVER_LETTER))
                .applicationUsedCoverLetterState(
                        record.getApplicationUsedCoverLetterState())
                .applicationUsedAt(record.getApplicationUsedAt())
                .status(record.getStatus())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .appliedAt(record.getAppliedAt())
                .version(record.getVersion())
                .build();
    }

    private DocumentVersionReference reference(
            String documentId,
            String familyId,
            Integer version,
            String sha256,
            com.jobseekercopilot.applicationtracker.dto.DocumentSourceType sourceType,
            String originalContentSha256,
            LocalDateTime selectedAt,
            com.jobseekercopilot.applicationtracker.dto.DocumentEvidenceProvenance provenance,
            com.jobseekercopilot.applicationtracker.dto.DocumentGroundingState groundingState,
            ApplicationRecord record,
            DocumentType type) {
        if (documentId == null) {
            return null;
        }
        if (familyId == null || version == null || sha256 == null) {
            return null;
        }
        try {
            return DocumentVersionReference.builder()
                    .ownerId(record.getUserId())
                    .documentId(UUID.fromString(documentId))
                    .documentFamilyId(UUID.fromString(familyId))
                    .jobId(selectionJobId(record))
                    .documentType(type)
                    .version(version)
                    .contentSha256(sha256)
                    .sourceType(sourceType)
                    .originalContentSha256(originalContentSha256)
                    .selectedAt(selectedAt)
                    .evidenceProvenance(provenance)
                    .groundingState(groundingState)
                    .build();
        } catch (IllegalArgumentException legacyIdentifier) {
            return null;
        }
    }
}
