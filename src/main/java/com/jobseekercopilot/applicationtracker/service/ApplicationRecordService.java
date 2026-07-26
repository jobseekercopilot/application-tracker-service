package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    @Transactional
    public ApplicationRecordResponse createApplication(
            String ownerId,
            CreateApplicationRequest request) {
        long startedAt = System.nanoTime();
        DocumentVersionReference cvReference = documentReferenceVerifier.verify(
                ownerId, request.getCvDocumentId(), request.getJobId(), DocumentType.CV);
        DocumentVersionReference coverLetterReference = documentReferenceVerifier.verify(
                ownerId,
                request.getCoverLetterDocumentId(),
                request.getJobId(),
                DocumentType.COVER_LETTER);
        ApplicationRecord record = ApplicationRecord.builder()
                .userId(ownerId)
                .jobId(request.getJobId())
                .canonicalJobId(firstNonBlank(request.getCanonicalJobId(), request.getJobId()))
                .provider(firstNonBlank(request.getProvider(), "REED"))
                .externalJobId(firstNonBlank(request.getExternalJobId(), request.getJobId()))
                .jobTitle(request.getJobTitle())
                .companyName(request.getCompanyName())
                .location(request.getLocation())
                .cvDocumentId(cvReference.getDocumentId().toString())
                .cvDocumentFamilyId(cvReference.getDocumentFamilyId().toString())
                .cvDocumentVersion(cvReference.getVersion())
                .cvDocumentContentSha256(cvReference.getContentSha256())
                .coverLetterDocumentId(coverLetterReference.getDocumentId().toString())
                .coverLetterDocumentFamilyId(
                        coverLetterReference.getDocumentFamilyId().toString())
                .coverLetterDocumentVersion(coverLetterReference.getVersion())
                .coverLetterDocumentContentSha256(
                        coverLetterReference.getContentSha256())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build();

        ApplicationRecord saved = repository.save(record);
        log.info("Application created provider={} cvDocumentLinked={} coverLetterDocumentLinked={} durationMs={}",
                saved.getProvider(),
                saved.getCvDocumentId() != null,
                saved.getCoverLetterDocumentId() != null,
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(saved);
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
        long startedAt = System.nanoTime();
        ApplicationRecord record = findOwnedApplicationForUpdate(ownerId, id);

        ApplicationStatus previousStatus = record.getStatus();
        ApplicationStatus newStatus = parseSupportedStatus(request.getStatus());
        if (newStatus != ApplicationStatus.DOCUMENTS_GENERATED
                && record.getApplicationUsedCvDocumentId() == null) {
            freezeApplicationUsedReferences(record);
        }
        record.setStatus(newStatus);
        if (newStatus == ApplicationStatus.APPLIED && record.getAppliedAt() == null) {
            record.setAppliedAt(LocalDateTime.now());
        }

        ApplicationRecord updated = repository.save(record);
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
        ApplicationRecord record = findOwnedApplicationForUpdate(ownerId, id);
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
            setCurrentCvReference(record, reference);
        } else if ("COVER_LETTER".equals(documentType)) {
            DocumentVersionReference reference = documentReferenceVerifier.verify(
                    ownerId,
                    request.getDocumentId(),
                    record.getJobId(),
                    DocumentType.COVER_LETTER);
            setCurrentCoverLetterReference(record, reference);
        } else {
            throw new InvalidStatusException("Invalid documentType: " + request.getDocumentType());
        }
        ApplicationRecord updated = repository.save(record);
        log.info("Owner-scoped application document reference updated documentType={}",
                documentType);
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteApplication(String ownerId, UUID id) {
        ApplicationRecord record = findOwnedApplicationForUpdate(ownerId, id);
        if (record.getApplicationUsedCvDocumentId() != null) {
            throw new InvalidStatusException(
                    "Submitted applications require retention-aware deletion.");
        }
        repository.delete(record);
    }

    @Transactional
    public WithdrawGeneratedApplicationResponse withdrawGeneratedApplication(
            String ownerId,
            UUID id,
            String authorization) {
        long startedAt = System.nanoTime();
        ApplicationRecord record = findOwnedApplicationForUpdate(ownerId, id);

        if (record.getStatus() != ApplicationStatus.DOCUMENTS_GENERATED) {
            log.warn("Invalid owner-scoped generated application withdraw status={}",
                    record.getStatus());
            throw new InvalidStatusException("Generated application can only be withdrawn before applying");
        }

        repository.delete(record);
        log.info("Owner-scoped generated application withdrawn durationMs={}",
                (System.nanoTime() - startedAt) / 1_000_000);
        return WithdrawGeneratedApplicationResponse.builder()
                .applicationId(id)
                .status("NEW")
                .withdrawn(true)
                .message("Generated application withdrawn and reset to new.")
                .build();
    }

    private ApplicationRecordResponse mapToResponse(ApplicationRecord record) {
        return ApplicationRecordResponse.builder()
                .id(record.getId())
                .userId(record.getUserId())
                .jobId(record.getJobId())
                .canonicalJobId(record.getCanonicalJobId())
                .provider(record.getProvider())
                .externalJobId(record.getExternalJobId())
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
                .build();
    }

    private void freezeApplicationUsedReferences(ApplicationRecord record) {
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
        record.setApplicationUsedAt(LocalDateTime.now());
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

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
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
