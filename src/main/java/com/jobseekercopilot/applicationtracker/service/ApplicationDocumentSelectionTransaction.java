package com.jobseekercopilot.applicationtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentSelectionCommand;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.ApplicationCreationConflictException;
import com.jobseekercopilot.applicationtracker.exception.ApplicationSelectionVersionConflictException;
import com.jobseekercopilot.applicationtracker.exception.IdempotencyConflictException;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentSelectionCommandRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ApplicationDocumentSelectionTransaction {

    private final ApplicationDocumentSelectionCommandRepository commandRepository;
    private final ApplicationRecordRepository applicationRepository;
    private final ApplicationEventRecorder eventRecorder;
    private final ApplicationDocumentReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

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

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ApplicationRecord findApplicationForVerification(
            String ownerId, UUID applicationId, long expectedVersion) {
        ApplicationRecord record = applicationRepository
                .findByIdAndUserId(applicationId, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        requireEditable(record);
        if (record.getVersion() != expectedVersion) {
            throw new ApplicationSelectionVersionConflictException(
                    toResponse(record));
        }
        return record;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplicationRecordResponse apply(
            String ownerId,
            UUID applicationId,
            String idempotencyKey,
            String fingerprint,
            long expectedVersion,
            DocumentVersionReference cv,
            DocumentVersionReference coverLetter,
            ApplicationCommandActor actor) {
        Optional<ApplicationDocumentSelectionCommand> existing = commandRepository
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
        requireEditable(record);
        if (record.getVersion() != expectedVersion) {
            throw new ApplicationSelectionVersionConflictException(
                    toResponse(record));
        }

        boolean initialSelection = !commandRepository
                .existsByUserIdAndApplicationId(ownerId, applicationId);
        boolean cvChanged = !sameDocument(record.getCvDocumentId(), cv);
        boolean coverLetterChanged =
                !sameDocument(record.getCoverLetterDocumentId(), coverLetter);
        ApplicationRecordResponse outcome;
        if (cvChanged || coverLetterChanged) {
            String reason = selectionReason(
                    record.getCvDocumentId(),
                    cv,
                    record.getCoverLetterDocumentId(),
                    coverLetter);
            LocalDateTime selectedAt = LocalDateTime.now(clock);
            if (cvChanged) {
                setCv(record, cv, selectedAt);
            }
            if (coverLetterChanged) {
                setCoverLetter(record, coverLetter, selectedAt);
            }
            ApplicationRecord updated = applicationRepository.saveAndFlush(record);
            eventRecorder.recordApplicationDocumentSelection(
                    updated, initialSelection, Instant.now(), actor, reason);
            reconciliationService.markHealthy(updated);
            outcome = toResponse(updated);
        } else {
            if (initialSelection) {
                eventRecorder.recordApplicationDocumentSelection(
                        record,
                        true,
                        Instant.now(),
                        actor,
                        selectionReason(
                                record.getCvDocumentId(),
                                cv,
                                record.getCoverLetterDocumentId(),
                                coverLetter));
            }
            outcome = toResponse(record);
        }

        commandRepository.saveAndFlush(
                ApplicationDocumentSelectionCommand.builder()
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
                        "Document selection conflicted with another request. Retry with the same idempotency key."));
    }

    private ApplicationRecordResponse replay(
            ApplicationDocumentSelectionCommand command,
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
                    "Stored document-selection outcome is unreadable",
                    corruptedLedger);
        }
    }

    private String writeOutcome(ApplicationRecordResponse outcome) {
        try {
            return objectMapper.writeValueAsString(outcome);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException(
                    "Document-selection outcome could not be stored",
                    serializationFailure);
        }
    }

    private void requireEditable(ApplicationRecord record) {
        boolean editable = record.getStatus() == ApplicationStatus.SAVED
                || record.getStatus() == ApplicationStatus.DOCUMENTS_GENERATED;
        boolean frozen = record.getApplicationUsedCvDocumentId() != null
                || record.getApplicationUsedCoverLetterDocumentId() != null;
        if (!editable || frozen || record.getActiveDocumentWorkflowId() != null) {
            throw new InvalidStatusException(
                    "Document selections cannot be changed after application or while a document workflow is active.");
        }
    }

    private boolean sameDocument(
            String currentDocumentId, DocumentVersionReference desired) {
        return desired == null
                ? currentDocumentId == null
                : desired.getDocumentId().toString().equals(currentDocumentId);
    }

    private String selectionReason(
            String previousCv,
            DocumentVersionReference cv,
            String previousCoverLetter,
            DocumentVersionReference coverLetter) {
        return "Atomic application document selections saved: CV "
                + change(previousCv, cv)
                + "; cover letter "
                + change(previousCoverLetter, coverLetter)
                + ".";
    }

    private String change(
            String previousDocumentId, DocumentVersionReference desired) {
        if (desired == null) {
            return previousDocumentId == null ? "remains omitted" : "cleared";
        }
        return previousDocumentId == null ? "selected" : "changed";
    }

    private void setCv(
            ApplicationRecord record,
            DocumentVersionReference reference,
            LocalDateTime selectedAt) {
        record.setCvDocumentId(value(reference, Value.DOCUMENT_ID));
        record.setCvDocumentFamilyId(value(reference, Value.FAMILY_ID));
        record.setCvDocumentVersion(reference == null ? null : reference.getVersion());
        record.setCvDocumentContentSha256(
                reference == null ? null : reference.getContentSha256());
        record.setCvDocumentSourceType(
                reference == null ? null : reference.getSourceType());
        record.setCvDocumentOriginalContentSha256(
                reference == null
                        ? null
                        : reference.getOriginalContentSha256());
        record.setCvDocumentSelectedAt(reference == null ? null : selectedAt);
        record.setCvDocumentEvidenceProvenance(
                reference == null ? null : reference.getEvidenceProvenance());
        record.setCvDocumentGroundingState(
                reference == null ? null : reference.getGroundingState());
    }

    private void setCoverLetter(
            ApplicationRecord record,
            DocumentVersionReference reference,
            LocalDateTime selectedAt) {
        record.setCoverLetterDocumentId(value(reference, Value.DOCUMENT_ID));
        record.setCoverLetterDocumentFamilyId(value(reference, Value.FAMILY_ID));
        record.setCoverLetterDocumentVersion(
                reference == null ? null : reference.getVersion());
        record.setCoverLetterDocumentContentSha256(
                reference == null ? null : reference.getContentSha256());
        record.setCoverLetterDocumentSourceType(
                reference == null ? null : reference.getSourceType());
        record.setCoverLetterDocumentOriginalContentSha256(
                reference == null
                        ? null
                        : reference.getOriginalContentSha256());
        record.setCoverLetterDocumentSelectedAt(
                reference == null ? null : selectedAt);
        record.setCoverLetterDocumentEvidenceProvenance(
                reference == null ? null : reference.getEvidenceProvenance());
        record.setCoverLetterDocumentGroundingState(
                reference == null ? null : reference.getGroundingState());
    }

    private String value(DocumentVersionReference reference, Value value) {
        if (reference == null) {
            return null;
        }
        return switch (value) {
            case DOCUMENT_ID -> reference.getDocumentId().toString();
            case FAMILY_ID -> reference.getDocumentFamilyId().toString();
        };
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
                .cvDocumentReference(cvReference(record))
                .coverLetterDocumentReference(coverLetterReference(record))
                .applicationUsedCvDocumentReference(usedCvReference(record))
                .applicationUsedCvState(record.getApplicationUsedCvState())
                .applicationUsedCoverLetterDocumentReference(
                        usedCoverLetterReference(record))
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

    private DocumentVersionReference cvReference(ApplicationRecord record) {
        return reference(
                record.getCvDocumentId(),
                record.getCvDocumentFamilyId(),
                record.getCvDocumentVersion(),
                record.getCvDocumentContentSha256(),
                record.getCvDocumentSourceType(),
                record.getCvDocumentOriginalContentSha256(),
                record.getCvDocumentSelectedAt(),
                record.getCvDocumentEvidenceProvenance(),
                record.getCvDocumentGroundingState(),
                com.jobseekercopilot.applicationtracker.dto.DocumentType.CV,
                selectionJobId(record),
                record.getUserId());
    }

    private DocumentVersionReference coverLetterReference(ApplicationRecord record) {
        return reference(
                record.getCoverLetterDocumentId(),
                record.getCoverLetterDocumentFamilyId(),
                record.getCoverLetterDocumentVersion(),
                record.getCoverLetterDocumentContentSha256(),
                record.getCoverLetterDocumentSourceType(),
                record.getCoverLetterDocumentOriginalContentSha256(),
                record.getCoverLetterDocumentSelectedAt(),
                record.getCoverLetterDocumentEvidenceProvenance(),
                record.getCoverLetterDocumentGroundingState(),
                com.jobseekercopilot.applicationtracker.dto.DocumentType.COVER_LETTER,
                selectionJobId(record),
                record.getUserId());
    }

    private DocumentVersionReference usedCvReference(ApplicationRecord record) {
        return reference(
                record.getApplicationUsedCvDocumentId(),
                record.getApplicationUsedCvDocumentFamilyId(),
                record.getApplicationUsedCvDocumentVersion(),
                record.getApplicationUsedCvDocumentContentSha256(),
                record.getApplicationUsedCvDocumentSourceType(),
                record.getApplicationUsedCvDocumentOriginalContentSha256(),
                record.getApplicationUsedCvDocumentSelectedAt(),
                record.getApplicationUsedCvEvidenceProvenance(),
                record.getApplicationUsedCvGroundingState(),
                com.jobseekercopilot.applicationtracker.dto.DocumentType.CV,
                selectionJobId(record),
                record.getUserId());
    }

    private DocumentVersionReference usedCoverLetterReference(
            ApplicationRecord record) {
        return reference(
                record.getApplicationUsedCoverLetterDocumentId(),
                record.getApplicationUsedCoverLetterDocumentFamilyId(),
                record.getApplicationUsedCoverLetterDocumentVersion(),
                record.getApplicationUsedCoverLetterDocumentContentSha256(),
                record.getApplicationUsedCoverLetterDocumentSourceType(),
                record.getApplicationUsedCoverLetterDocumentOriginalContentSha256(),
                record.getApplicationUsedCoverLetterDocumentSelectedAt(),
                record.getApplicationUsedCoverLetterEvidenceProvenance(),
                record.getApplicationUsedCoverLetterGroundingState(),
                com.jobseekercopilot.applicationtracker.dto.DocumentType.COVER_LETTER,
                selectionJobId(record),
                record.getUserId());
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
            com.jobseekercopilot.applicationtracker.dto.DocumentType type,
            String jobId,
            String ownerId) {
        if (documentId == null) {
            return null;
        }
        if (familyId == null || version == null || sha256 == null) {
            return null;
        }
        try {
            return DocumentVersionReference.builder()
                    .ownerId(ownerId)
                    .documentId(UUID.fromString(documentId))
                    .documentFamilyId(UUID.fromString(familyId))
                    .jobId(jobId)
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

    private enum Value {
        DOCUMENT_ID,
        FAMILY_ID
    }

    private String selectionJobId(ApplicationRecord record) {
        return record.getCanonicalJobId() == null
                        || record.getCanonicalJobId().isBlank()
                ? record.getJobId()
                : record.getCanonicalJobId();
    }
}
