package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationLifecycle;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.ApplicationVersionConflictException;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApplicationRecordService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRecordService.class);

    private static final Set<ApplicationStatus> SUPPORTED_STATUS_UPDATES = EnumSet.of(
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
    private final DocumentReferenceVerifier documentReferenceVerifier;
    private final ApplicationCreationService applicationCreationService;
    private final ApplicationEventRecorder eventRecorder;
    private final ApplicationWithdrawalWorkflowService withdrawalWorkflowService;
    private final ApplicationDocumentReconciliationService
            reconciliationService;

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

    @Transactional
    public ApplicationRecordResponse updateStatus(
            String ownerId,
            UUID id,
            UpdateStatusRequest request) {
        return updateStatus(
                ownerId,
                id,
                request,
                ApplicationCommandActor.user(ownerId));
    }

    @Transactional
    public ApplicationRecordResponse updateStatus(
            String ownerId,
            UUID id,
            UpdateStatusRequest request,
            ApplicationCommandActor actor) {
        long startedAt = System.nanoTime();
        ApplicationRecord record = findOwnedApplication(ownerId, id);
        requireNoActiveDocumentWorkflow(record);

        ApplicationStatus previousStatus = record.getStatus();
        ApplicationStatus newStatus = parseSupportedStatus(request.getStatus());
        if (previousStatus == newStatus) {
            return mapToResponse(record);
        }
        ApplicationLifecycle.requireTransition(previousStatus, newStatus);
        if (request.getExpectedVersion() != null
                && request.getExpectedVersion() != record.getVersion()) {
            throw new ApplicationVersionConflictException();
        }
        if (newStatus != ApplicationStatus.DOCUMENTS_GENERATED) {
            reconciliationService.requireHealthy(record);
        }
        Instant occurredAt = resolveOccurredAt(record, request.getOccurredAt());
        if (newStatus != ApplicationStatus.DOCUMENTS_GENERATED
                && record.getApplicationUsedCvDocumentId() == null) {
            freezeApplicationUsedReferences(
                    record, LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        }
        record.setStatus(newStatus);
        if (newStatus == ApplicationStatus.APPLIED && record.getAppliedAt() == null) {
            record.setAppliedAt(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        }

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
        if (record.getStatus() != ApplicationStatus.DOCUMENTS_GENERATED
                || record.getApplicationUsedCvDocumentId() != null) {
            throw new InvalidStatusException("Documents cannot be replaced after the application has been marked as applied.");
        }
        String documentType = request.getDocumentType() == null ? "" : request.getDocumentType().toUpperCase(Locale.ROOT);
        if ("CV".equals(documentType)) {
            DocumentVersionReference reference = documentReferenceVerifier.verify(
                    ownerId,
                    request.getDocumentId(),
                    record.getJobId(),
                    DocumentType.CV);
            if (reference.equals(currentCvReference(record))) {
                return mapToResponse(record);
            }
            setCurrentCvReference(record, reference);
        } else if ("COVER_LETTER".equals(documentType)) {
            DocumentVersionReference reference = documentReferenceVerifier.verify(
                    ownerId,
                    request.getDocumentId(),
                    record.getJobId(),
                    DocumentType.COVER_LETTER);
            if (reference.equals(currentCoverLetterReference(record))) {
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
        if (record.getApplicationUsedCvDocumentId() != null) {
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
                .cvDocumentReference(currentCvReference(record))
                .coverLetterDocumentReference(currentCoverLetterReference(record))
                .applicationUsedCvDocumentReference(
                        applicationUsedCvReference(record))
                .applicationUsedCoverLetterDocumentReference(
                        applicationUsedCoverLetterReference(record))
                .applicationUsedAt(record.getApplicationUsedAt())
                .status(record.getStatus())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .appliedAt(record.getAppliedAt())
                .version(record.getVersion())
                .build();
    }

    private void freezeApplicationUsedReferences(
            ApplicationRecord record, LocalDateTime occurredAt) {
        DocumentVersionReference cv = currentCvReference(record);
        DocumentVersionReference coverLetter = currentCoverLetterReference(record);
        if (cv == null || coverLetter == null) {
            throw new InvalidDocumentReferenceException();
        }
        record.setApplicationUsedCvDocumentId(cv.getDocumentId().toString());
        record.setApplicationUsedCvDocumentFamilyId(
                cv.getDocumentFamilyId().toString());
        record.setApplicationUsedCvDocumentVersion(cv.getVersion());
        record.setApplicationUsedCvDocumentContentSha256(cv.getContentSha256());
        record.setApplicationUsedCoverLetterDocumentId(
                coverLetter.getDocumentId().toString());
        record.setApplicationUsedCoverLetterDocumentFamilyId(
                coverLetter.getDocumentFamilyId().toString());
        record.setApplicationUsedCoverLetterDocumentVersion(
                coverLetter.getVersion());
        record.setApplicationUsedCoverLetterDocumentContentSha256(
                coverLetter.getContentSha256());
        record.setApplicationUsedAt(occurredAt);
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
    }

    private void setCurrentCoverLetterReference(
            ApplicationRecord record, DocumentVersionReference reference) {
        record.setCoverLetterDocumentId(reference.getDocumentId().toString());
        record.setCoverLetterDocumentFamilyId(
                reference.getDocumentFamilyId().toString());
        record.setCoverLetterDocumentVersion(reference.getVersion());
        record.setCoverLetterDocumentContentSha256(reference.getContentSha256());
    }

    private DocumentVersionReference currentCvReference(ApplicationRecord record) {
        return reference(
                record.getCvDocumentId(),
                record.getCvDocumentFamilyId(),
                record.getJobId(),
                DocumentType.CV,
                record.getCvDocumentVersion(),
                record.getCvDocumentContentSha256());
    }

    private DocumentVersionReference currentCoverLetterReference(
            ApplicationRecord record) {
        return reference(
                record.getCoverLetterDocumentId(),
                record.getCoverLetterDocumentFamilyId(),
                record.getJobId(),
                DocumentType.COVER_LETTER,
                record.getCoverLetterDocumentVersion(),
                record.getCoverLetterDocumentContentSha256());
    }

    private DocumentVersionReference applicationUsedCvReference(
            ApplicationRecord record) {
        return reference(
                record.getApplicationUsedCvDocumentId(),
                record.getApplicationUsedCvDocumentFamilyId(),
                record.getJobId(),
                DocumentType.CV,
                record.getApplicationUsedCvDocumentVersion(),
                record.getApplicationUsedCvDocumentContentSha256());
    }

    private DocumentVersionReference applicationUsedCoverLetterReference(
            ApplicationRecord record) {
        return reference(
                record.getApplicationUsedCoverLetterDocumentId(),
                record.getApplicationUsedCoverLetterDocumentFamilyId(),
                record.getJobId(),
                DocumentType.COVER_LETTER,
                record.getApplicationUsedCoverLetterDocumentVersion(),
                record.getApplicationUsedCoverLetterDocumentContentSha256());
    }

    private DocumentVersionReference reference(
            String documentId,
            String familyId,
            String jobId,
            DocumentType type,
            Integer version,
            String sha256) {
        if (documentId == null
                || familyId == null
                || version == null
                || sha256 == null) {
            return null;
        }
        try {
            return DocumentVersionReference.builder()
                    .documentId(UUID.fromString(documentId))
                    .documentFamilyId(UUID.fromString(familyId))
                    .jobId(jobId)
                    .documentType(type)
                    .version(version)
                    .contentSha256(sha256)
                    .build();
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
                + ". Allowed values: DOCUMENTS_GENERATED, APPLIED, INTERVIEW, UNSUCCESSFUL, OFFER, ACCEPTED, REJECTED_BY_USER, WITHDRAWN");
    }
}
