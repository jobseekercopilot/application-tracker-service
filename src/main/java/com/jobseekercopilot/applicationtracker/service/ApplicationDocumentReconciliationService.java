package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.DocumentReferenceReconciliationResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentReconciliation;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.DocumentReferenceReconciliationStatus;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceUnavailableException;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceReconciliationConflictException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentReconciliationRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ApplicationDocumentReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(ApplicationDocumentReconciliationService.class);
    private static final String RECONCILER_ID =
            "application-document-reference-reconciler";

    private final ApplicationDocumentReconciliationRepository
            reconciliationRepository;
    private final ApplicationRecordRepository applicationRepository;
    private final DocumentReferenceVerifier documentReferenceVerifier;
    private final ApplicationEventRecorder eventRecorder;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(
            initialDelayString =
                    "${application-tracker.reconciliation.initial-delay-ms:45000}",
            fixedDelayString =
                    "${application-tracker.reconciliation.delay-ms:30000}")
    public void reconcileNextBatch() {
        List<UUID> applicationIds = reconciliationRepository
                .findNextBatch(PageRequest.of(0, 50))
                .stream()
                .map(ApplicationDocumentReconciliation::getApplicationId)
                .toList();
        for (UUID applicationId : applicationIds) {
            try {
                reconcile(applicationId);
            } catch (RuntimeException exception) {
                log.warn(
                        "Application document-reference reconciliation failed applicationId={} error={}",
                        applicationId,
                        exception.getClass().getSimpleName());
            }
        }
    }

    public void reconcile(UUID applicationId) {
        ApplicationRecord snapshot = applicationRepository
                .findById(applicationId)
                .orElse(null);
        if (snapshot == null) {
            return;
        }
        Evaluation evaluation = evaluate(snapshot);
        transaction().executeWithoutResult(status ->
                apply(snapshot, evaluation));
    }

    public DocumentReferenceReconciliationResponse status(
            String ownerId, UUID applicationId) {
        ApplicationRecord application = applicationRepository
                .findByIdAndUserId(applicationId, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        return reconciliationRepository
                .findByApplicationIdAndUserId(applicationId, ownerId)
                .map(this::response)
                .orElseGet(() -> pendingResponse(application));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markHealthy(ApplicationRecord application) {
        LocalDateTime now = utcNow();
        ApplicationDocumentReconciliation reconciliation =
                reconciliationRepository
                        .findForUpdateByApplicationId(application.getId())
                        .orElse(null);
        if (reconciliation == null) {
            reconciliation = newReconciliation(application);
        }
        reconciliation.setUserId(application.getUserId());
        reconciliation.setStatus(
                DocumentReferenceReconciliationStatus.HEALTHY);
        reconciliation.setIssueCodes(null);
        reconciliation.setCheckedAt(now);
        reconciliation.setLastHealthyAt(now);
        reconciliation.setApplicationRecordVersion(application.getVersion());
        reconciliationRepository.saveAndFlush(reconciliation);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireHealthy(ApplicationRecord application) {
        if (!hasAnyReference(application)) {
            return;
        }
        ApplicationDocumentReconciliation reconciliation =
                reconciliationRepository
                        .findByApplicationIdAndUserId(
                                application.getId(), application.getUserId())
                        .orElseThrow(() -> new DocumentReferenceReconciliationConflictException(
                                "Application document references are awaiting reconciliation."));
        if (reconciliation.getStatus()
                        != DocumentReferenceReconciliationStatus.HEALTHY
                && reconciliation.getStatus()
                        != DocumentReferenceReconciliationStatus.REPAIRED) {
            throw new DocumentReferenceReconciliationConflictException(
                    "Application document references are not currently verified. Retry after reconciliation.");
        }
    }

    private Evaluation evaluate(ApplicationRecord application) {
        Map<ReferenceRole, DocumentVersionReference> observed =
                new EnumMap<>(ReferenceRole.class);
        Set<String> issues = new LinkedHashSet<>();
        boolean unavailable = false;
        boolean invalid = false;

        for (StoredReference stored : references(application)) {
            if (!StringUtils.hasText(stored.documentId())) {
                if (stored.required() || stored.hasMetadata()) {
                    issues.add(stored.role().code("MISSING"));
                    invalid = true;
                }
                continue;
            }

            UUID documentId;
            try {
                documentId = UUID.fromString(stored.documentId());
            } catch (IllegalArgumentException exception) {
                issues.add(stored.role().code("INVALID_ID"));
                invalid = true;
                continue;
            }

            try {
                DocumentVersionReference reference =
                        documentReferenceVerifier.verify(
                                application.getUserId(),
                                documentId,
                                application.getJobId(),
                                stored.role().documentType());
                if (!stored.compatibleWith(reference)) {
                    issues.add(stored.role().code("METADATA_MISMATCH"));
                    invalid = true;
                    continue;
                }
                observed.put(stored.role(), reference);
            } catch (InvalidDocumentReferenceException exception) {
                issues.add(stored.role().code("INVALID"));
                invalid = true;
            } catch (DocumentReferenceUnavailableException exception) {
                issues.add(stored.role().code("UNAVAILABLE"));
                unavailable = true;
            }
        }

        return new Evaluation(
                observed,
                List.copyOf(issues),
                invalid,
                unavailable);
    }

    private void apply(
            ApplicationRecord snapshot,
            Evaluation evaluation) {
        ApplicationRecord application = applicationRepository
                .findForUpdateByIdAndUserId(
                        snapshot.getId(), snapshot.getUserId())
                .orElse(null);
        if (application == null) {
            return;
        }
        ApplicationDocumentReconciliation reconciliation =
                reconciliationRepository
                        .findForUpdateByApplicationId(application.getId())
                        .orElse(null);
        if (reconciliation == null) {
            reconciliation = newReconciliation(application);
        }
        LocalDateTime now = utcNow();
        reconciliation.setUserId(application.getUserId());
        reconciliation.setCheckedAt(now);
        reconciliation.setAttemptCount(
                reconciliation.getAttemptCount() + 1);

        if (application.getVersion() != snapshot.getVersion()) {
            reconciliation.setStatus(
                    DocumentReferenceReconciliationStatus.PENDING);
            reconciliation.setIssueCodes(
                    "APPLICATION_CHANGED_DURING_CHECK");
            reconciliation.setApplicationRecordVersion(
                    application.getVersion());
            reconciliationRepository.saveAndFlush(reconciliation);
            return;
        }

        boolean repaired = applyMissingMetadata(
                application, evaluation.observed());
        if (repaired) {
            ApplicationRecord repairedApplication =
                    applicationRepository.saveAndFlush(application);
            eventRecorder.recordDocumentReferencesReconciled(
                    repairedApplication,
                    Instant.now(),
                    ApplicationCommandActor.system(RECONCILER_ID),
                    reconciliationReason(evaluation));
            reconciliation.setLastRepairedAt(now);
            reconciliation.setRepairCount(
                    reconciliation.getRepairCount() + 1);
            application = repairedApplication;
        }

        DocumentReferenceReconciliationStatus outcome =
                outcome(evaluation, repaired);
        reconciliation.setStatus(outcome);
        reconciliation.setIssueCodes(
                evaluation.issues().isEmpty()
                        ? null
                        : String.join(",", evaluation.issues()));
        reconciliation.setApplicationRecordVersion(
                application.getVersion());
        if (outcome == DocumentReferenceReconciliationStatus.HEALTHY
                || outcome
                        == DocumentReferenceReconciliationStatus.REPAIRED) {
            reconciliation.setLastHealthyAt(now);
        }
        reconciliationRepository.saveAndFlush(reconciliation);
        log.info(
                "Application document references reconciled applicationId={} status={} repaired={} issues={}",
                application.getId(),
                outcome,
                repaired,
                evaluation.issues().size());
    }

    private boolean applyMissingMetadata(
            ApplicationRecord application,
            Map<ReferenceRole, DocumentVersionReference> observed) {
        boolean repaired = false;
        for (Map.Entry<ReferenceRole, DocumentVersionReference> entry
                : observed.entrySet()) {
            repaired |= entry.getKey()
                    .fillMissing(application, entry.getValue());
        }
        return repaired;
    }

    private DocumentReferenceReconciliationStatus outcome(
            Evaluation evaluation, boolean repaired) {
        if (evaluation.invalid()) {
            return DocumentReferenceReconciliationStatus.INVALID;
        }
        if (evaluation.unavailable()) {
            return DocumentReferenceReconciliationStatus.UNAVAILABLE;
        }
        return repaired
                ? DocumentReferenceReconciliationStatus.REPAIRED
                : DocumentReferenceReconciliationStatus.HEALTHY;
    }

    private String reconciliationReason(Evaluation evaluation) {
        if (evaluation.issues().isEmpty()) {
            return "Missing immutable document-reference metadata repaired from approved owner-bound Store references.";
        }
        return "Safe immutable document-reference metadata repaired; unresolved findings: "
                + String.join(",", evaluation.issues())
                + ".";
    }

    private List<StoredReference> references(ApplicationRecord application) {
        boolean generated =
                application.getProvenance() == ApplicationProvenance.GENERATED;
        boolean prepared =
                application.getStatus()
                        == ApplicationStatus.DOCUMENTS_GENERATED;
        boolean currentPairRequired = generated || prepared;
        boolean usedPairRequired = hasCompleteUsedPair(application);
        List<StoredReference> references = new ArrayList<>(4);
        references.add(new StoredReference(
                ReferenceRole.CURRENT_CV,
                application.getCvDocumentId(),
                application.getCvDocumentFamilyId(),
                application.getCvDocumentVersion(),
                application.getCvDocumentContentSha256(),
                currentPairRequired));
        references.add(new StoredReference(
                ReferenceRole.CURRENT_COVER_LETTER,
                application.getCoverLetterDocumentId(),
                application.getCoverLetterDocumentFamilyId(),
                application.getCoverLetterDocumentVersion(),
                application.getCoverLetterDocumentContentSha256(),
                currentPairRequired));
        references.add(new StoredReference(
                ReferenceRole.USED_CV,
                application.getApplicationUsedCvDocumentId(),
                application.getApplicationUsedCvDocumentFamilyId(),
                application.getApplicationUsedCvDocumentVersion(),
                application.getApplicationUsedCvDocumentContentSha256(),
                usedPairRequired));
        references.add(new StoredReference(
                ReferenceRole.USED_COVER_LETTER,
                application.getApplicationUsedCoverLetterDocumentId(),
                application.getApplicationUsedCoverLetterDocumentFamilyId(),
                application.getApplicationUsedCoverLetterDocumentVersion(),
                application.getApplicationUsedCoverLetterDocumentContentSha256(),
                usedPairRequired));
        return references;
    }

    private boolean hasAnyReference(ApplicationRecord application) {
        return application.getCvDocumentId() != null
                || application.getCoverLetterDocumentId() != null
                || application.getApplicationUsedCvDocumentId() != null
                || application.getApplicationUsedCoverLetterDocumentId() != null;
    }

    private boolean hasCompleteUsedPair(ApplicationRecord application) {
        return application.getApplicationUsedCvDocumentId() != null
                && application.getApplicationUsedCoverLetterDocumentId() != null;
    }

    private ApplicationDocumentReconciliation newReconciliation(
            ApplicationRecord application) {
        return ApplicationDocumentReconciliation.builder()
                .applicationId(application.getId())
                .userId(application.getUserId())
                .status(DocumentReferenceReconciliationStatus.PENDING)
                .applicationRecordVersion(application.getVersion())
                .build();
    }

    private DocumentReferenceReconciliationResponse response(
            ApplicationDocumentReconciliation reconciliation) {
        return DocumentReferenceReconciliationResponse.builder()
                .applicationId(reconciliation.getApplicationId())
                .status(reconciliation.getStatus())
                .issueCodes(splitIssues(reconciliation.getIssueCodes()))
                .checkedAt(reconciliation.getCheckedAt())
                .lastHealthyAt(reconciliation.getLastHealthyAt())
                .lastRepairedAt(reconciliation.getLastRepairedAt())
                .attemptCount(reconciliation.getAttemptCount())
                .repairCount(reconciliation.getRepairCount())
                .applicationRecordVersion(
                        reconciliation.getApplicationRecordVersion())
                .build();
    }

    private DocumentReferenceReconciliationResponse pendingResponse(
            ApplicationRecord application) {
        return DocumentReferenceReconciliationResponse.builder()
                .applicationId(application.getId())
                .status(DocumentReferenceReconciliationStatus.PENDING)
                .issueCodes(List.of())
                .attemptCount(0)
                .repairCount(0)
                .applicationRecordVersion(application.getVersion())
                .build();
    }

    private List<String> splitIssues(String issueCodes) {
        return !StringUtils.hasText(issueCodes)
                ? List.of()
                : List.of(issueCodes.split(","));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private record Evaluation(
            Map<ReferenceRole, DocumentVersionReference> observed,
            List<String> issues,
            boolean invalid,
            boolean unavailable) {
    }

    private record StoredReference(
            ReferenceRole role,
            String documentId,
            String familyId,
            Integer version,
            String contentSha256,
            boolean required) {

        private boolean hasMetadata() {
            return familyId != null
                    || version != null
                    || contentSha256 != null;
        }

        private boolean compatibleWith(
                DocumentVersionReference reference) {
            return (familyId == null
                            || Objects.equals(
                                    familyId,
                                    reference.getDocumentFamilyId()
                                            .toString()))
                    && (version == null
                            || Objects.equals(
                                    version, reference.getVersion()))
                    && (contentSha256 == null
                            || Objects.equals(
                                    contentSha256,
                                    reference.getContentSha256()));
        }
    }

    private enum ReferenceRole {
        CURRENT_CV(DocumentType.CV),
        CURRENT_COVER_LETTER(DocumentType.COVER_LETTER),
        USED_CV(DocumentType.CV),
        USED_COVER_LETTER(DocumentType.COVER_LETTER);

        private final DocumentType documentType;

        ReferenceRole(DocumentType documentType) {
            this.documentType = documentType;
        }

        private DocumentType documentType() {
            return documentType;
        }

        private String code(String suffix) {
            return name() + "_" + suffix;
        }

        private boolean fillMissing(
                ApplicationRecord application,
                DocumentVersionReference reference) {
            return switch (this) {
                case CURRENT_CV -> fillCurrentCv(application, reference);
                case CURRENT_COVER_LETTER ->
                        fillCurrentCoverLetter(application, reference);
                case USED_CV -> fillUsedCv(application, reference);
                case USED_COVER_LETTER ->
                        fillUsedCoverLetter(application, reference);
            };
        }

        private boolean fillCurrentCv(
                ApplicationRecord application,
                DocumentVersionReference reference) {
            boolean changed = false;
            if (application.getCvDocumentFamilyId() == null) {
                application.setCvDocumentFamilyId(
                        reference.getDocumentFamilyId().toString());
                changed = true;
            }
            if (application.getCvDocumentVersion() == null) {
                application.setCvDocumentVersion(reference.getVersion());
                changed = true;
            }
            if (application.getCvDocumentContentSha256() == null) {
                application.setCvDocumentContentSha256(
                        reference.getContentSha256());
                changed = true;
            }
            if (application.getCvDocumentEvidenceProvenance() == null
                    && reference.getEvidenceProvenance() != null) {
                application.setCvDocumentEvidenceProvenance(
                        reference.getEvidenceProvenance());
                changed = true;
            }
            if (application.getCvDocumentGroundingState() == null
                    && reference.getGroundingState() != null) {
                application.setCvDocumentGroundingState(
                        reference.getGroundingState());
                changed = true;
            }
            return changed;
        }

        private boolean fillCurrentCoverLetter(
                ApplicationRecord application,
                DocumentVersionReference reference) {
            boolean changed = false;
            if (application.getCoverLetterDocumentFamilyId() == null) {
                application.setCoverLetterDocumentFamilyId(
                        reference.getDocumentFamilyId().toString());
                changed = true;
            }
            if (application.getCoverLetterDocumentVersion() == null) {
                application.setCoverLetterDocumentVersion(
                        reference.getVersion());
                changed = true;
            }
            if (application.getCoverLetterDocumentContentSha256() == null) {
                application.setCoverLetterDocumentContentSha256(
                        reference.getContentSha256());
                changed = true;
            }
            if (application.getCoverLetterDocumentEvidenceProvenance() == null
                    && reference.getEvidenceProvenance() != null) {
                application.setCoverLetterDocumentEvidenceProvenance(
                        reference.getEvidenceProvenance());
                changed = true;
            }
            if (application.getCoverLetterDocumentGroundingState() == null
                    && reference.getGroundingState() != null) {
                application.setCoverLetterDocumentGroundingState(
                        reference.getGroundingState());
                changed = true;
            }
            return changed;
        }

        private boolean fillUsedCv(
                ApplicationRecord application,
                DocumentVersionReference reference) {
            boolean changed = false;
            if (application.getApplicationUsedCvDocumentFamilyId() == null) {
                application.setApplicationUsedCvDocumentFamilyId(
                        reference.getDocumentFamilyId().toString());
                changed = true;
            }
            if (application.getApplicationUsedCvDocumentVersion() == null) {
                application.setApplicationUsedCvDocumentVersion(
                        reference.getVersion());
                changed = true;
            }
            if (application
                    .getApplicationUsedCvDocumentContentSha256() == null) {
                application.setApplicationUsedCvDocumentContentSha256(
                        reference.getContentSha256());
                changed = true;
            }
            if (application.getApplicationUsedCvEvidenceProvenance() == null
                    && reference.getEvidenceProvenance() != null) {
                application.setApplicationUsedCvEvidenceProvenance(
                        reference.getEvidenceProvenance());
                changed = true;
            }
            if (application.getApplicationUsedCvGroundingState() == null
                    && reference.getGroundingState() != null) {
                application.setApplicationUsedCvGroundingState(
                        reference.getGroundingState());
                changed = true;
            }
            return changed;
        }

        private boolean fillUsedCoverLetter(
                ApplicationRecord application,
                DocumentVersionReference reference) {
            boolean changed = false;
            if (application
                    .getApplicationUsedCoverLetterDocumentFamilyId() == null) {
                application.setApplicationUsedCoverLetterDocumentFamilyId(
                        reference.getDocumentFamilyId().toString());
                changed = true;
            }
            if (application
                    .getApplicationUsedCoverLetterDocumentVersion() == null) {
                application.setApplicationUsedCoverLetterDocumentVersion(
                        reference.getVersion());
                changed = true;
            }
            if (application
                    .getApplicationUsedCoverLetterDocumentContentSha256()
                    == null) {
                application.setApplicationUsedCoverLetterDocumentContentSha256(
                        reference.getContentSha256());
                changed = true;
            }
            if (application
                            .getApplicationUsedCoverLetterEvidenceProvenance()
                    == null
                    && reference.getEvidenceProvenance() != null) {
                application.setApplicationUsedCoverLetterEvidenceProvenance(
                        reference.getEvidenceProvenance());
                changed = true;
            }
            if (application.getApplicationUsedCoverLetterGroundingState()
                    == null
                    && reference.getGroundingState() != null) {
                application.setApplicationUsedCoverLetterGroundingState(
                        reference.getGroundingState());
                changed = true;
            }
            return changed;
        }
    }
}
