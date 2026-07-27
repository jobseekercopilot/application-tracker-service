package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentReconciliation;
import com.jobseekercopilot.applicationtracker.entity.ApplicationEventType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.DocumentReferenceReconciliationStatus;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceUnavailableException;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceReconciliationConflictException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentReconciliationRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationEventRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;

@SpringBootTest
class ApplicationDocumentReconciliationIntegrationTest {

    private static final String OWNER = "reconciliation-owner";
    private static final String JOB_ID = "reconciliation-job";
    private static final String CV_SHA = "a".repeat(64);
    private static final String COVER_SHA = "b".repeat(64);

    @Autowired
    private ApplicationDocumentReconciliationService reconciliationService;

    @Autowired
    private ApplicationRecordService applicationService;

    @Autowired
    private ApplicationRecordRepository applicationRepository;

    @Autowired
    private ApplicationDocumentReconciliationRepository
            reconciliationRepository;

    @Autowired
    private ApplicationEventRepository eventRepository;

    @MockBean
    private DocumentReferenceVerifier documentReferenceVerifier;

    @BeforeEach
    void setUp() {
        reconciliationRepository.deleteAll();
        applicationRepository.deleteAll();
        when(documentReferenceVerifier.verify(
                        anyString(),
                        any(UUID.class),
                        anyString(),
                        any(DocumentType.class)))
                .thenAnswer(invocation -> reference(
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
    }

    @Test
    void safelyHydratesMissingMetadataAndRecordsDurableRepair() {
        UUID cvId = UUID.randomUUID();
        UUID coverId = UUID.randomUUID();
        ApplicationRecord application = saveApplication(cvId, coverId);
        savePending(application);

        reconciliationService.reconcile(application.getId());

        ApplicationRecord repaired = applicationRepository
                .findById(application.getId())
                .orElseThrow();
        assertThat(repaired.getCvDocumentFamilyId())
                .isEqualTo(cvId.toString());
        assertThat(repaired.getCvDocumentVersion()).isEqualTo(1);
        assertThat(repaired.getCvDocumentContentSha256())
                .isEqualTo(CV_SHA);
        assertThat(repaired.getCoverLetterDocumentFamilyId())
                .isEqualTo(coverId.toString());
        assertThat(repaired.getCoverLetterDocumentVersion())
                .isEqualTo(1);
        assertThat(repaired.getCoverLetterDocumentContentSha256())
                .isEqualTo(COVER_SHA);

        ApplicationDocumentReconciliation state =
                reconciliationRepository
                        .findById(application.getId())
                        .orElseThrow();
        assertThat(state.getStatus())
                .isEqualTo(
                        DocumentReferenceReconciliationStatus.REPAIRED);
        assertThat(state.getIssueCodes()).isNull();
        assertThat(state.getAttemptCount()).isEqualTo(1);
        assertThat(state.getRepairCount()).isEqualTo(1);
        assertThat(state.getCheckedAt()).isNotNull();
        assertThat(state.getLastHealthyAt()).isNotNull();
        assertThat(state.getLastRepairedAt()).isNotNull();
        assertThat(state.getApplicationRecordVersion())
                .isEqualTo(repaired.getVersion());

        assertThat(eventRepository
                        .findByApplicationIdAndUserIdOrderByOccurredAtAscRecordedAtAscIdAsc(
                                application.getId(),
                                OWNER,
                                PageRequest.of(0, 10))
                        .getContent())
                .singleElement()
                .extracting(event -> event.getEventType())
                .isEqualTo(
                        ApplicationEventType
                                .DOCUMENT_REFERENCES_RECONCILED);
    }

    @Test
    void reportsMetadataMismatchWithoutOverwritingHistoricalEvidence() {
        UUID cvId = UUID.randomUUID();
        UUID coverId = UUID.randomUUID();
        ApplicationRecord application = saveApplication(cvId, coverId);
        String conflictingFamily = UUID.randomUUID().toString();
        application.setCvDocumentFamilyId(conflictingFamily);
        application.setCvDocumentVersion(99);
        application.setCvDocumentContentSha256("c".repeat(64));
        applicationRepository.saveAndFlush(application);
        savePending(application);

        reconciliationService.reconcile(application.getId());

        ApplicationRecord unchanged = applicationRepository
                .findById(application.getId())
                .orElseThrow();
        assertThat(unchanged.getCvDocumentFamilyId())
                .isEqualTo(conflictingFamily);
        assertThat(unchanged.getCvDocumentVersion()).isEqualTo(99);
        assertThat(unchanged.getCvDocumentContentSha256())
                .isEqualTo("c".repeat(64));

        ApplicationDocumentReconciliation state =
                reconciliationRepository
                        .findById(application.getId())
                        .orElseThrow();
        assertThat(state.getStatus())
                .isEqualTo(
                        DocumentReferenceReconciliationStatus.INVALID);
        assertThat(state.getIssueCodes())
                .contains("CURRENT_CV_METADATA_MISMATCH");
        assertThat(state.getRepairCount()).isEqualTo(1);
        assertThat(eventRepository.countByApplicationIdAndUserId(
                        application.getId(), OWNER))
                .isEqualTo(1);
    }

    @Test
    void reportsUnavailableStoreAndKeepsFindingRetryableByTheScheduler() {
        ApplicationRecord application =
                saveApplication(UUID.randomUUID(), UUID.randomUUID());
        savePending(application);
        when(documentReferenceVerifier.verify(
                        anyString(),
                        any(UUID.class),
                        anyString(),
                        any(DocumentType.class)))
                .thenThrow(new DocumentReferenceUnavailableException());

        reconciliationService.reconcileNextBatch();

        ApplicationDocumentReconciliation state =
                reconciliationRepository
                        .findById(application.getId())
                        .orElseThrow();
        assertThat(state.getStatus())
                .isEqualTo(
                        DocumentReferenceReconciliationStatus.UNAVAILABLE);
        assertThat(state.getIssueCodes())
                .contains("CURRENT_CV_UNAVAILABLE")
                .contains("CURRENT_COVER_LETTER_UNAVAILABLE");
        assertThat(state.getAttemptCount()).isEqualTo(1);
        assertThat(state.getRepairCount()).isZero();
    }

    @Test
    void blocksLifecycleProgressWhenReconciliationHasAConfirmedIssue() {
        ApplicationRecord application =
                saveApplication(UUID.randomUUID(), UUID.randomUUID());
        ApplicationDocumentReconciliation state = savePending(application);
        state.setStatus(DocumentReferenceReconciliationStatus.INVALID);
        state.setIssueCodes("CURRENT_CV_INVALID");
        reconciliationRepository.saveAndFlush(state);

        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("APPLIED");

        assertThatThrownBy(() -> applicationService.updateStatus(
                        OWNER,
                        application.getId(),
                        request,
                        ApplicationCommandActor.user(OWNER)))
                .isInstanceOf(DocumentReferenceReconciliationConflictException.class)
                .hasMessageContaining("not currently verified");
        assertThat(applicationRepository
                        .findById(application.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);
    }

    private ApplicationRecord saveApplication(
            UUID cvId, UUID coverLetterId) {
        return applicationRepository.saveAndFlush(
                ApplicationRecord.builder()
                        .userId(OWNER)
                        .jobId(JOB_ID)
                        .canonicalJobId(JOB_ID)
                        .provider("LEGACY")
                        .externalJobId(JOB_ID)
                        .provenance(ApplicationProvenance.GENERATED)
                        .jobTitle("Reconciliation Engineer")
                        .companyName("Example Ltd")
                        .cvDocumentId(cvId.toString())
                        .coverLetterDocumentId(
                                coverLetterId.toString())
                        .status(ApplicationStatus.DOCUMENTS_GENERATED)
                        .build());
    }

    private ApplicationDocumentReconciliation savePending(
            ApplicationRecord application) {
        return reconciliationRepository.saveAndFlush(
                ApplicationDocumentReconciliation.builder()
                        .applicationId(application.getId())
                        .userId(application.getUserId())
                        .status(
                                DocumentReferenceReconciliationStatus.PENDING)
                        .applicationRecordVersion(application.getVersion())
                        .build());
    }

    private DocumentVersionReference reference(
            UUID documentId,
            String jobId,
            DocumentType type) {
        return DocumentVersionReference.builder()
                .documentId(documentId)
                .documentFamilyId(documentId)
                .jobId(jobId)
                .documentType(type)
                .version(1)
                .contentSha256(
                        type == DocumentType.CV
                                ? CV_SHA
                                : COVER_SHA)
                .build();
    }
}
