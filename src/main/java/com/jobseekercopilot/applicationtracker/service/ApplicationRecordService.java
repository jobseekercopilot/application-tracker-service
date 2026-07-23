package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

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
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${services.document-store-service.base-url:}")
    private String documentStoreBaseUrl;

    @Transactional
    public ApplicationRecordResponse createApplication(CreateApplicationRequest request) {
        long startedAt = System.nanoTime();
        ApplicationRecord record = ApplicationRecord.builder()
                .userId(request.getUserId())
                .jobId(request.getJobId())
                .canonicalJobId(firstNonBlank(request.getCanonicalJobId(), request.getJobId()))
                .provider(firstNonBlank(request.getProvider(), "REED"))
                .externalJobId(firstNonBlank(request.getExternalJobId(), request.getJobId()))
                .jobTitle(request.getJobTitle())
                .companyName(request.getCompanyName())
                .location(request.getLocation())
                .cvDocumentId(request.getCvDocumentId())
                .coverLetterDocumentId(request.getCoverLetterDocumentId())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build();

        ApplicationRecord saved = repository.save(record);
        log.info("Application created applicationId={} userId={} jobId={} canonicalJobId={} provider={} cvDocumentLinked={} coverLetterDocumentLinked={} durationMs={}",
                saved.getId(),
                saved.getUserId(),
                saved.getJobId(),
                saved.getCanonicalJobId(),
                saved.getProvider(),
                saved.getCvDocumentId() != null,
                saved.getCoverLetterDocumentId() != null,
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(saved);
    }

    public ApplicationRecordResponse getApplicationById(UUID id) {
        ApplicationRecord record = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));
        return mapToResponse(record);
    }

    public List<ApplicationRecordResponse> getApplicationsForUser(String userId) {
        long startedAt = System.nanoTime();
        List<ApplicationRecordResponse> responses = repository.findByUserId(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        log.info("Application lookup by user userId={} count={} durationMs={}",
                userId,
                responses.size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return responses;
    }

    public ApplicationRecordResponse getApplicationByDocumentId(String documentId) {
        ApplicationRecord record = repository.findByCvDocumentIdOrCoverLetterDocumentIdOrderByUpdatedAtDesc(documentId, documentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Application not found for document id: " + documentId));
        return mapToResponse(record);
    }

    @Transactional
    public ApplicationRecordResponse updateStatus(UUID id, UpdateStatusRequest request) {
        long startedAt = System.nanoTime();
        ApplicationRecord record = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));

        ApplicationStatus previousStatus = record.getStatus();
        ApplicationStatus newStatus = parseSupportedStatus(request.getStatus());
        record.setStatus(newStatus);
        if (newStatus == ApplicationStatus.APPLIED && record.getAppliedAt() == null) {
            record.setAppliedAt(LocalDateTime.now());
        }

        ApplicationRecord updated = repository.save(record);
        log.info("Application status updated applicationId={} userId={} previousStatus={} newStatus={} durationMs={}",
                id,
                updated.getUserId(),
                previousStatus,
                newStatus,
                (System.nanoTime() - startedAt) / 1_000_000);
        return mapToResponse(updated);
    }

    @Transactional
    public ApplicationRecordResponse updateDocumentReference(UUID id, UpdateDocumentReferenceRequest request) {
        ApplicationRecord record = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));
        if (record.getStatus() != ApplicationStatus.DOCUMENTS_GENERATED) {
            throw new InvalidStatusException("Documents cannot be replaced after the application has been marked as applied.");
        }
        String documentType = request.getDocumentType() == null ? "" : request.getDocumentType().toUpperCase(Locale.ROOT);
        if ("CV".equals(documentType)) {
            record.setCvDocumentId(request.getDocumentId());
        } else if ("COVER_LETTER".equals(documentType)) {
            record.setCoverLetterDocumentId(request.getDocumentId());
        } else {
            throw new InvalidStatusException("Invalid documentType: " + request.getDocumentType());
        }
        ApplicationRecord updated = repository.save(record);
        log.info("Application document reference updated applicationId={} documentType={} documentId={}",
                id,
                documentType,
                request.getDocumentId());
        return mapToResponse(updated);
    }

    @Transactional
    public void deleteApplication(UUID id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Application not found with id: " + id);
        }
        repository.deleteById(id);
    }

    @Transactional
    public WithdrawGeneratedApplicationResponse withdrawGeneratedApplication(UUID id) {
        long startedAt = System.nanoTime();
        ApplicationRecord record = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));

        if (record.getStatus() != ApplicationStatus.DOCUMENTS_GENERATED) {
            log.warn("Invalid generated application withdraw applicationId={} userId={} status={}",
                    id,
                    record.getUserId(),
                    record.getStatus());
            throw new InvalidStatusException("Generated application can only be withdrawn before applying");
        }

        deactivateApplicationDocuments(id);
        repository.delete(record);
        log.info("Generated application withdrawn applicationId={} userId={} jobId={} durationMs={}",
                id,
                record.getUserId(),
                record.getJobId(),
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
                .status(record.getStatus())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .appliedAt(record.getAppliedAt())
                .build();
    }

    private void deactivateApplicationDocuments(UUID applicationId) {
        if (documentStoreBaseUrl == null || documentStoreBaseUrl.isBlank()) {
            return;
        }
        try {
            restTemplate.postForEntity(
                    documentStoreBaseUrl + "/api/v1/documents/applications/{applicationId}/deactivate",
                    null,
                    Void.class,
                    applicationId.toString());
        } catch (RestClientException exception) {
            log.warn("Document deactivation failed during generated application withdraw applicationId={} error={}",
                    applicationId,
                    exception.getClass().getSimpleName());
        }
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
