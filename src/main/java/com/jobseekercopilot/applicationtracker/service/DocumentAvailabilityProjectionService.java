package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.DocumentAvailabilityResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentAvailabilityState;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentAvailabilityRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.DocumentAvailabilityProjection;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.repository.DocumentAvailabilityProjectionRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentAvailabilityProjectionService {
    private final DocumentAvailabilityProjectionRepository projectionRepository;
    private final ApplicationRecordRepository applicationRepository;

    @Transactional
    public DocumentAvailabilityResponse update(
            String ownerId,
            UUID documentId,
            UpdateDocumentAvailabilityRequest request) {
        validate(request);
        LocalDateTime occurredAt =
                request.occurredAt().truncatedTo(ChronoUnit.MICROS);
        String unavailableReason =
                request.availability() == DocumentAvailabilityState.AVAILABLE
                        ? null
                        : request.unavailableReason().trim();
        DocumentAvailabilityProjection projection = projectionRepository
                .findByOwnerIdAndDocumentId(ownerId, documentId)
                .orElseGet(() -> DocumentAvailabilityProjection.builder()
                        .ownerId(ownerId)
                        .documentId(documentId)
                        .build());
        if (projection.getAvailability() == DocumentAvailabilityState.PURGED
                && request.availability() != DocumentAvailabilityState.PURGED) {
            throw new InvalidRequestException(
                    "A purged document availability projection cannot be restored.");
        }
        if (projection.getLifecycleOccurredAt() != null
                && occurredAt.equals(projection.getLifecycleOccurredAt())) {
            if (request.availability() == projection.getAvailability()
                    && java.util.Objects.equals(
                            unavailableReason,
                            projection.getUnavailableReason())) {
                return response(projection);
            }
            throw new InvalidRequestException(
                    "One lifecycle timestamp cannot represent different document availability states.");
        }
        if (projection.getLifecycleOccurredAt() != null
                && occurredAt.isBefore(projection.getLifecycleOccurredAt())) {
            throw new InvalidRequestException(
                    "A stale document availability update cannot replace newer state.");
        }

        projection.setAvailability(request.availability());
        projection.setUnavailableReason(unavailableReason);
        projection.setUnavailableAt(
                request.availability() == DocumentAvailabilityState.AVAILABLE
                        ? null
                        : occurredAt);
        projection.setLifecycleOccurredAt(occurredAt);
        projection = projectionRepository.saveAndFlush(projection);

        if (request.availability() == DocumentAvailabilityState.PURGED) {
            List<ApplicationRecord> associations = applicationRepository
                    .findByUserIdAndDocumentId(ownerId, documentId.toString());
            associations.forEach(record -> scrubPurgedReference(
                    record, documentId.toString()));
            applicationRepository.saveAllAndFlush(associations);
        }
        return response(projection);
    }

    private void validate(UpdateDocumentAvailabilityRequest request) {
        boolean available =
                request.availability() == DocumentAvailabilityState.AVAILABLE;
        boolean hasReason = request.unavailableReason() != null
                && !request.unavailableReason().isBlank();
        if (available == hasReason
                || (hasReason && request.unavailableReason().trim().length() > 64)) {
            throw new InvalidRequestException(
                    "Unavailable document state requires a reason of at most 64 characters; available state must omit it.");
        }
    }

    private void scrubPurgedReference(
            ApplicationRecord record,
            String documentId) {
        if (documentId.equals(record.getCvDocumentId())) {
            record.setCvDocumentContentSha256(null);
            record.setCvDocumentEvidenceProvenance(null);
            record.setCvDocumentGroundingState(null);
        }
        if (documentId.equals(record.getCoverLetterDocumentId())) {
            record.setCoverLetterDocumentContentSha256(null);
            record.setCoverLetterDocumentEvidenceProvenance(null);
            record.setCoverLetterDocumentGroundingState(null);
        }
        if (documentId.equals(record.getApplicationUsedCvDocumentId())) {
            record.setApplicationUsedCvDocumentContentSha256(null);
            record.setApplicationUsedCvEvidenceProvenance(null);
            record.setApplicationUsedCvGroundingState(null);
        }
        if (documentId.equals(
                record.getApplicationUsedCoverLetterDocumentId())) {
            record.setApplicationUsedCoverLetterDocumentContentSha256(null);
            record.setApplicationUsedCoverLetterEvidenceProvenance(null);
            record.setApplicationUsedCoverLetterGroundingState(null);
        }
    }

    private DocumentAvailabilityResponse response(
            DocumentAvailabilityProjection projection) {
        return new DocumentAvailabilityResponse(
                projection.getDocumentId(),
                projection.getAvailability(),
                projection.getUnavailableReason(),
                projection.getUnavailableAt(),
                projection.getUpdatedAt());
    }
}
