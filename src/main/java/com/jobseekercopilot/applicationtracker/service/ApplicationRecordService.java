package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentEvidenceProvenance;
import com.jobseekercopilot.applicationtracker.dto.DocumentApplicationAssociation;
import com.jobseekercopilot.applicationtracker.dto.DocumentApplicationAssociationState;
import com.jobseekercopilot.applicationtracker.dto.DocumentApplicationAssociationsResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentAvailabilityState;
import com.jobseekercopilot.applicationtracker.dto.DocumentGroundingState;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationLifecycle;
import com.jobseekercopilot.applicationtracker.entity.ApplicationActorType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.DocumentAvailabilityProjection;
import com.jobseekercopilot.applicationtracker.exception.ApplicationVersionConflictException;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.repository.DocumentAvailabilityProjectionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApplicationRecordService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRecordService.class);

    private static final Set<ApplicationStatus> SUPPORTED_STATUS_UPDATES = EnumSet.of(
            ApplicationStatus.SAVED,
            ApplicationStatus.DOCUMENTS_GENERATED,
            ApplicationStatus.APPLIED,
            ApplicationStatus.INTERVIEW,
            ApplicationStatus.UNSUCCESSFUL,
            ApplicationStatus.OFFER,
            ApplicationStatus.ACCEPTED,
            ApplicationStatus.REJECTED_BY_USER,
            ApplicationStatus.WITHDRAWN
    );

    private final ApplicationRecordRepository repository;
    private final DocumentAvailabilityProjectionRepository
            availabilityProjectionRepository;
    private final DocumentReferenceVerifier documentReferenceVerifier;
    private final ApplicationCreationService applicationCreationService;
    private final ApplicationEventRecorder eventRecorder;
    private final ApplicationWithdrawalWorkflowService withdrawalWorkflowService;
    private final ApplicationDocumentReconciliationService
            reconciliationService;
    private final ApplicationAppliedFreezeService appliedFreezeService;

    public ApplicationRecordResponse createApplication(
            String ownerId,
            CreateApplicationRequest request) {
        return createApplication(ownerId, null, request).application();
    }

    public ApplicationCreationResult createApplication(
            String ownerId,
            String idempotencyKey,
            CreateApplicationRequest request) {
        return createApplication(
                ownerId,
                idempotencyKey,
                request,
                ApplicationCommandActor.user(ownerId));
    }

    public ApplicationCreationResult createApplication(
            String ownerId,
            String idempotencyKey,
            CreateApplicationRequest request,
            ApplicationCommandActor actor) {
        long startedAt = System.nanoTime();
        ApplicationCreationOutcome outcome =
                applicationCreationService.createApplication(
                        ownerId, idempotencyKey, request, actor);
        ApplicationRecord saved = outcome.record();
        log.info("Application create resolved provider={} provenance={} created={} cvDocumentLinked={} coverLetterDocumentLinked={} durationMs={}",
                saved.getProvider(),
                saved.getProvenance(),
                outcome.created(),
                saved.getCvDocumentId() != null,
                saved.getCoverLetterDocumentId() != null,
                (System.nanoTime() - startedAt) / 1_000_000);
        return new ApplicationCreationResult(
                mapToResponse(saved), outcome.created());
    }

    public ApplicationRecordResponse getApplicationById(String ownerId, UUID id) {
        ApplicationRecord record = findOwnedApplication(ownerId, id);
        return mapToResponse(record);
    }

    public List<ApplicationRecordResponse> getApplicationsForUser(String ownerId) {
        long startedAt = System.nanoTime();
        List<ApplicationRecordResponse> responses = repository.findByUserId(ownerId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        log.info("Owner-scoped application lookup count={} durationMs={}",
                responses.size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return responses;
    }

    public ApplicationRecordResponse getApplicationByDocumentId(
            String ownerId,
            String documentId) {
        ApplicationRecord record = repository.findByUserIdAndDocumentId(ownerId, documentId)
                .stream()
                .findFirst()
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        return mapToResponse(record);
    }

    public DocumentApplicationAssociationsResponse getDocumentAssociations(
            String ownerId,
            UUID documentId) {
        List<DocumentApplicationAssociation> associations = repository
                .findByUserIdAndDocumentId(ownerId, documentId.toString())
                .stream()
                .map(record -> association(record, documentId.toString()))
                .toList();
        return new DocumentApplicationAssociationsResponse(
                documentId,
                associations.size(),
                associations);
    }

    private DocumentApplicationAssociation association(
            ApplicationRecord record,
            String documentId) {
        boolean frozenCv = documentId.equals(
                record.getApplicationUsedCvDocumentId());
        boolean frozenCoverLetter = documentId.equals(
                record.getApplicationUsedCoverLetterDocumentId());
        boolean frozen = frozenCv || frozenCoverLetter;
        DocumentType type = frozenCv || (!frozen && documentId.equals(
                        record.getCvDocumentId()))
                ? DocumentType.CV
                : DocumentType.COVER_LETTER;
        LocalDateTime selectedAt = frozenCv
                ? firstNonNull(
                        record.getApplicationUsedCvDocumentSelectedAt(),
                        record.getApplicationUsedAt())
                : frozenCoverLetter
                        ? firstNonNull(
                                record.getApplicationUsedCoverLetterDocumentSelectedAt(),
                                record.getApplicationUsedAt())
                        : type == DocumentType.CV
                                ? record.getCvDocumentSelectedAt()
                                : record.getCoverLetterDocumentSelectedAt();
        return new DocumentApplicationAssociation(
                record.getId(),
                type,
                frozen
                        ? DocumentApplicationAssociationState.FROZEN_USED
                        : DocumentApplicationAssociationState.DRAFT_SELECTED,
                record.getStatus(),
                selectedAt);
    }

    private LocalDateTime firstNonNull(
            LocalDateTime preferred, LocalDateTime fallback) {
        return preferred == null ? fallback : preferred;
    }

    @Transactional
    public ApplicationRecordResponse updateStatus(
            String ownerId,
            UUID id,
            UpdateStatusRequest request) {
        return updateStatus(
                ownerId,
                id,
                request,
                legacyApplyKey(id, request),
                ApplicationCommandActor.user(ownerId));
    }

    @Transactional
    public ApplicationRecordResponse updateStatus(
            String ownerId,
            UUID id,
            UpdateStatusRequest request,
            ApplicationCommandActor actor) {
        return updateStatus(
                ownerId,
                id,
                request,
                legacyApplyKey(id, request),
                actor);
    }

    @Transactional
    public ApplicationRecordResponse updateStatus(
            String ownerId,
            UUID id,
            UpdateStatusRequest request,
            String idempotencyKey,
            ApplicationCommandActor actor) {
        long startedAt = System.nanoTime();
        ApplicationStatus newStatus = parseSupportedStatus(request.getStatus());
        if (newStatus == ApplicationStatus.APPLIED) {
            return appliedFreezeService.apply(
                    ownerId, id, idempotencyKey, request, actor);
        }
        ApplicationRecord record = findOwnedApplication(ownerId, id);
        requireNoActiveDocumentWorkflow(record);

        ApplicationStatus previousStatus = record.getStatus();
        requireActorCanTransition(actor, previousStatus, newStatus);
        if (previousStatus == newStatus) {
            return mapToResponse(record);
        }
        ApplicationLifecycle.requireTransition(previousStatus, newStatus);
        if (request.getExpectedVersion() != null
                && request.getExpectedVersion() != record.getVersion()) {
            throw new ApplicationVersionConflictException();
        }
        requireEligibleSavedTransition(record, previousStatus, newStatus);
        if (hasAnyDocumentReference(record)) {
            reconciliationService.requireHealthy(record);
        }
        Instant occurredAt = resolveOccurredAt(record, request.getOccurredAt());
        record.setStatus(newStatus);

        ApplicationRecord updated = repository.saveAndFlush(record);
        eventRecorder.recordStatusChanged(
                updated,
                previousStatus,
                occurredAt,
                actor,
                request.getReason());
        log.info("Owner-scoped application status updated previousStatus={} newStatus={} durationMs={}",
                previousStatus,
                newStatus,
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(updated);
    }

    @Transactional
    public ApplicationRecordResponse updateDocumentReference(
            String ownerId,
            UUID id,
            UpdateDocumentReferenceRequest request) {
        return updateDocumentReference(
                ownerId,
                id,
                request,
                ApplicationCommandActor.user(ownerId));
    }

    @Transactional
    public ApplicationRecordResponse updateDocumentReference(
            String ownerId,
            UUID id,
            UpdateDocumentReferenceRequest request,
            ApplicationCommandActor actor) {
        ApplicationRecord record = findOwnedApplicationForUpdate(ownerId, id);
        requireNoActiveDocumentWorkflow(record);
        if (!isDocumentSelectionEditable(record.getStatus())
                || record.getApplicationUsedCvDocumentId() != null) {
            throw new InvalidStatusException("Documents cannot be replaced after the application has been marked as applied.");
        }
        String documentType = request.getDocumentType() == null ? "" : request.getDocumentType().toUpperCase(Locale.ROOT);
        if ("CV".equals(documentType)) {
            DocumentVersionReference reference = documentReferenceVerifier.verify(
                    ownerId,
                    request.getDocumentId(),
                    record.getJobId(),
                    record.getId(),
                    DocumentType.CV);
            if (reference.equals(currentCvReference(record, Map.of()))) {
                return mapToResponse(record);
            }
            setCurrentCvReference(record, reference);
        } else if ("COVER_LETTER".equals(documentType)) {
            DocumentVersionReference reference = documentReferenceVerifier.verify(
                    ownerId,
                    request.getDocumentId(),
                    record.getJobId(),
                    record.getId(),
                    DocumentType.COVER_LETTER);
            if (reference.equals(
                    currentCoverLetterReference(record, Map.of()))) {
                return mapToResponse(record);
            }
            setCurrentCoverLetterReference(record, reference);
        } else {
            throw new InvalidStatusException("Invalid documentType: " + request.getDocumentType());
        }
        ApplicationRecord updated = repository.saveAndFlush(record);
        eventRecorder.recordDocumentReferenceChanged(
                updated,
                Instant.now(),
                actor,
                documentType + " current approved reference replaced.");
        reconciliationService.markHealthy(updated);
        log.info("Owner-scoped application document reference updated documentType={}",
                documentType);
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteApplication(String ownerId, UUID id) {
        deleteApplication(ownerId, id, ApplicationCommandActor.user(ownerId));
    }

    @Transactional
    public void deleteApplication(
            String ownerId, UUID id, ApplicationCommandActor actor) {
        ApplicationRecord record = findOwnedApplicationForUpdate(ownerId, id);
        requireNoActiveDocumentWorkflow(record);
        if (record.getApplicationUsedAt() != null
                || record.getApplicationUsedCvDocumentId() != null
                || record.getApplicationUsedCoverLetterDocumentId() != null) {
            throw new InvalidStatusException(
                    "Submitted applications require retention-aware deletion.");
        }
        eventRecorder.recordDeletion(record, Instant.now(), actor);
        repository.delete(record);
    }

    public WithdrawGeneratedApplicationResponse withdrawGeneratedApplication(
            String ownerId,
            UUID id,
            String authorization) {
        return withdrawGeneratedApplication(
                ownerId,
                id,
                authorization,
                ApplicationCommandActor.user(ownerId));
    }

    public WithdrawGeneratedApplicationResponse withdrawGeneratedApplication(
            String ownerId,
            UUID id,
            String authorization,
            ApplicationCommandActor actor) {
        return withdrawalWorkflowService.withdraw(ownerId, id, actor);
    }

    public WithdrawGeneratedApplicationResponse generatedWithdrawalStatus(
            String ownerId, UUID id) {
        return withdrawalWorkflowService.status(ownerId, id);
    }

    private ApplicationRecordResponse mapToResponse(ApplicationRecord record) {
        Map<UUID, DocumentAvailabilityProjection> availability =
                availabilityFor(record);
        return ApplicationRecordResponse.builder()
                .id(record.getId())
                .userId(record.getUserId())
                .jobId(record.getJobId())
                .canonicalJobId(record.getCanonicalJobId())
                .provider(record.getProvider())
                .externalJobId(record.getExternalJobId())
                .listingUrl(record.getListingUrl())
                .applyUrl(record.getApplyUrl())
                .attributionLabel(record.getAttributionLabel())
                .attributionSourceUrl(record.getAttributionSourceUrl())
                .licenceUrl(record.getLicenceUrl())
                .disclaimer(record.getDisclaimer())
                .provenance(record.getProvenance())
                .jobTitle(record.getJobTitle())
                .companyName(record.getCompanyName())
                .location(record.getLocation())
                .cvDocumentId(record.getCvDocumentId())
                .coverLetterDocumentId(record.getCoverLetterDocumentId())
                .cvDocumentReference(currentCvReference(record, availability))
                .coverLetterDocumentReference(
                        currentCoverLetterReference(record, availability))
                .applicationUsedCvDocumentReference(
                        applicationUsedCvReference(record, availability))
                .applicationUsedCvState(record.getApplicationUsedCvState())
                .applicationUsedCoverLetterDocumentReference(
                        applicationUsedCoverLetterReference(
                                record, availability))
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

    private void requireEligibleSavedTransition(
            ApplicationRecord record,
            ApplicationStatus previousStatus,
            ApplicationStatus newStatus) {
        if (previousStatus != ApplicationStatus.SAVED) {
            return;
        }
        boolean complete = hasCompleteCurrentDocumentReferences(record);
        if (newStatus == ApplicationStatus.DOCUMENTS_GENERATED && !complete) {
            throw new InvalidDocumentReferenceException();
        }
    }

    private void requireActorCanTransition(
            ApplicationCommandActor actor,
            ApplicationStatus previousStatus,
            ApplicationStatus newStatus) {
        if (actor.actorType() != ApplicationActorType.SERVICE) {
            return;
        }
        boolean savedBridge =
                previousStatus == ApplicationStatus.SAVED
                        && newStatus
                                == ApplicationStatus.DOCUMENTS_GENERATED;
        boolean completedBridgeRetry =
                previousStatus == ApplicationStatus.DOCUMENTS_GENERATED
                        && newStatus
                                == ApplicationStatus.DOCUMENTS_GENERATED;
        if (!savedBridge && !completedBridgeRetry) {
            throw new AccessDeniedException(
                    "Producer status commands are limited to document preparation");
        }
    }

    private boolean isDocumentSelectionEditable(ApplicationStatus status) {
        return status == ApplicationStatus.SAVED
                || status == ApplicationStatus.DOCUMENTS_GENERATED;
    }

    private boolean hasAnyDocumentReference(ApplicationRecord record) {
        return record.getCvDocumentId() != null
                || record.getCoverLetterDocumentId() != null
                || hasAnyApplicationUsedDocumentReference(record);
    }

    private boolean hasCompleteCurrentDocumentReferences(
            ApplicationRecord record) {
        return currentCvReference(record, Map.of()) != null
                && currentCoverLetterReference(record, Map.of()) != null;
    }

    private boolean hasAnyApplicationUsedDocumentReference(
            ApplicationRecord record) {
        return record.getApplicationUsedCvDocumentId() != null
                || record.getApplicationUsedCoverLetterDocumentId() != null;
    }

    private String legacyApplyKey(UUID id, UpdateStatusRequest request) {
        if (!"APPLIED".equalsIgnoreCase(request.getStatus())) {
            return null;
        }
        return "legacy-apply-"
                + id
                + "-v"
                + (request.getExpectedVersion() == null
                        ? "missing"
                        : request.getExpectedVersion());
    }

    private Instant resolveOccurredAt(
            ApplicationRecord record, Instant requestedOccurredAt) {
        Instant now = Instant.now();
        if (requestedOccurredAt == null) {
            return now;
        }
        Instant occurredAt = requestedOccurredAt;
        if (record.getCreatedAt() != null
                && occurredAt.isBefore(
                        record.getCreatedAt().toInstant(ZoneOffset.UTC))) {
            throw new InvalidRequestException(
                    "occurredAt cannot be before the application was created.");
        }
        if (occurredAt.isAfter(now.plusSeconds(300))) {
            throw new InvalidRequestException(
                    "occurredAt cannot be more than five minutes in the future.");
        }
        return occurredAt;
    }

    private void setCurrentCvReference(
            ApplicationRecord record, DocumentVersionReference reference) {
        record.setCvDocumentId(reference.getDocumentId().toString());
        record.setCvDocumentFamilyId(reference.getDocumentFamilyId().toString());
        record.setCvDocumentVersion(reference.getVersion());
        record.setCvDocumentContentSha256(reference.getContentSha256());
        record.setCvDocumentSourceType(reference.getSourceType());
        record.setCvDocumentOriginalContentSha256(
                reference.getOriginalContentSha256());
        record.setCvDocumentSelectedAt(LocalDateTime.now(ZoneOffset.UTC));
        record.setCvDocumentEvidenceProvenance(
                reference.getEvidenceProvenance());
        record.setCvDocumentGroundingState(reference.getGroundingState());
    }

    private void setCurrentCoverLetterReference(
            ApplicationRecord record, DocumentVersionReference reference) {
        record.setCoverLetterDocumentId(reference.getDocumentId().toString());
        record.setCoverLetterDocumentFamilyId(
                reference.getDocumentFamilyId().toString());
        record.setCoverLetterDocumentVersion(reference.getVersion());
        record.setCoverLetterDocumentContentSha256(reference.getContentSha256());
        record.setCoverLetterDocumentSourceType(reference.getSourceType());
        record.setCoverLetterDocumentOriginalContentSha256(
                reference.getOriginalContentSha256());
        record.setCoverLetterDocumentSelectedAt(
                LocalDateTime.now(ZoneOffset.UTC));
        record.setCoverLetterDocumentEvidenceProvenance(
                reference.getEvidenceProvenance());
        record.setCoverLetterDocumentGroundingState(
                reference.getGroundingState());
    }

    private DocumentVersionReference currentCvReference(
            ApplicationRecord record,
            Map<UUID, DocumentAvailabilityProjection> availability) {
        return reference(
                record.getCvDocumentId(),
                record.getCvDocumentFamilyId(),
                selectionJobId(record),
                DocumentType.CV,
                record.getCvDocumentVersion(),
                record.getCvDocumentContentSha256(),
                record.getCvDocumentSourceType(),
                record.getCvDocumentOriginalContentSha256(),
                record.getCvDocumentSelectedAt(),
                record.getCvDocumentEvidenceProvenance(),
                record.getCvDocumentGroundingState(),
                record.getUserId(),
                availability);
    }

    private DocumentVersionReference currentCoverLetterReference(
            ApplicationRecord record,
            Map<UUID, DocumentAvailabilityProjection> availability) {
        return reference(
                record.getCoverLetterDocumentId(),
                record.getCoverLetterDocumentFamilyId(),
                selectionJobId(record),
                DocumentType.COVER_LETTER,
                record.getCoverLetterDocumentVersion(),
                record.getCoverLetterDocumentContentSha256(),
                record.getCoverLetterDocumentSourceType(),
                record.getCoverLetterDocumentOriginalContentSha256(),
                record.getCoverLetterDocumentSelectedAt(),
                record.getCoverLetterDocumentEvidenceProvenance(),
                record.getCoverLetterDocumentGroundingState(),
                record.getUserId(),
                availability);
    }

    private DocumentVersionReference applicationUsedCvReference(
            ApplicationRecord record,
            Map<UUID, DocumentAvailabilityProjection> availability) {
        return reference(
                record.getApplicationUsedCvDocumentId(),
                record.getApplicationUsedCvDocumentFamilyId(),
                selectionJobId(record),
                DocumentType.CV,
                record.getApplicationUsedCvDocumentVersion(),
                record.getApplicationUsedCvDocumentContentSha256(),
                record.getApplicationUsedCvDocumentSourceType(),
                record.getApplicationUsedCvDocumentOriginalContentSha256(),
                record.getApplicationUsedCvDocumentSelectedAt(),
                record.getApplicationUsedCvEvidenceProvenance(),
                record.getApplicationUsedCvGroundingState(),
                record.getUserId(),
                availability);
    }

    private DocumentVersionReference applicationUsedCoverLetterReference(
            ApplicationRecord record,
            Map<UUID, DocumentAvailabilityProjection> availability) {
        return reference(
                record.getApplicationUsedCoverLetterDocumentId(),
                record.getApplicationUsedCoverLetterDocumentFamilyId(),
                selectionJobId(record),
                DocumentType.COVER_LETTER,
                record.getApplicationUsedCoverLetterDocumentVersion(),
                record.getApplicationUsedCoverLetterDocumentContentSha256(),
                record.getApplicationUsedCoverLetterDocumentSourceType(),
                record.getApplicationUsedCoverLetterDocumentOriginalContentSha256(),
                record.getApplicationUsedCoverLetterDocumentSelectedAt(),
                record.getApplicationUsedCoverLetterEvidenceProvenance(),
                record.getApplicationUsedCoverLetterGroundingState(),
                record.getUserId(),
                availability);
    }

    private DocumentVersionReference reference(
            String documentId,
            String familyId,
            String jobId,
            DocumentType type,
            Integer version,
            String sha256,
            com.jobseekercopilot.applicationtracker.dto.DocumentSourceType sourceType,
            String originalContentSha256,
            LocalDateTime selectedAt,
            DocumentEvidenceProvenance evidenceProvenance,
            DocumentGroundingState groundingState,
            String ownerId,
            Map<UUID, DocumentAvailabilityProjection> availability) {
        if (documentId == null
                || familyId == null
                || version == null) {
            return null;
        }
        try {
            UUID parsedDocumentId = UUID.fromString(documentId);
            DocumentAvailabilityProjection projection =
                    availability.get(parsedDocumentId);
            return DocumentVersionReference.builder()
                    .ownerId(ownerId)
                    .documentId(parsedDocumentId)
                    .documentFamilyId(UUID.fromString(familyId))
                    .jobId(jobId)
                    .documentType(type)
                    .version(version)
                    .contentSha256(sha256)
                    .sourceType(sourceType)
                    .originalContentSha256(originalContentSha256)
                    .selectedAt(selectedAt)
                    .evidenceProvenance(evidenceProvenance)
                    .groundingState(groundingState)
                    .availability(projection == null
                            ? DocumentAvailabilityState.AVAILABLE
                            : projection.getAvailability())
                    .unavailableReason(projection == null
                            ? null
                            : projection.getUnavailableReason())
                    .unavailableAt(projection == null
                            ? null
                            : projection.getUnavailableAt())
                    .build();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Map<UUID, DocumentAvailabilityProjection> availabilityFor(
            ApplicationRecord record) {
        List<UUID> documentIds = java.util.stream.Stream.of(
                        record.getCvDocumentId(),
                        record.getCoverLetterDocumentId(),
                        record.getApplicationUsedCvDocumentId(),
                        record.getApplicationUsedCoverLetterDocumentId())
                .filter(java.util.Objects::nonNull)
                .map(this::uuidOrNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return java.util.Optional.ofNullable(availabilityProjectionRepository
                        .findByOwnerIdAndDocumentIdIn(
                                record.getUserId(), documentIds))
                .orElse(List.of())
                .stream()
                .collect(Collectors.toMap(
                        DocumentAvailabilityProjection::getDocumentId,
                        projection -> projection));
    }

    private UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String selectionJobId(ApplicationRecord record) {
        return record.getCanonicalJobId() == null
                        || record.getCanonicalJobId().isBlank()
                ? record.getJobId()
                : record.getCanonicalJobId();
    }

    private ApplicationRecord findOwnedApplication(String ownerId, UUID id) {
        return repository.findByIdAndUserId(id, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
    }

    private ApplicationRecord findOwnedApplicationForUpdate(
            String ownerId, UUID id) {
        return repository.findForUpdateByIdAndUserId(id, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
    }

    private void requireNoActiveDocumentWorkflow(ApplicationRecord record) {
        if (record.getActiveDocumentWorkflowId() != null) {
            throw new InvalidStatusException(
                    "Application has a document workflow in progress");
        }
    }

    private ApplicationStatus parseSupportedStatus(String status) {
        try {
            ApplicationStatus parsed = ApplicationStatus.valueOf(status.toUpperCase(Locale.ROOT));
            if (SUPPORTED_STATUS_UPDATES.contains(parsed)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to a consistent validation message.
        }
        log.warn("Invalid application status requested status={}", status);
        throw new InvalidStatusException("Invalid status: " + status
                + ". Allowed values: SAVED, DOCUMENTS_GENERATED, APPLIED, INTERVIEW, UNSUCCESSFUL, OFFER, ACCEPTED, REJECTED_BY_USER, WITHDRAWN");
    }
}
