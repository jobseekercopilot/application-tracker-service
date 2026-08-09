package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentApplicationAssociationState;
import com.jobseekercopilot.applicationtracker.dto.DocumentEvidenceProvenance;
import com.jobseekercopilot.applicationtracker.dto.DocumentGroundingState;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.EvidenceRevisionReference;
import com.jobseekercopilot.applicationtracker.dto.EvidenceSection;
import com.jobseekercopilot.applicationtracker.dto.ValidatedClaimLedger;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import com.jobseekercopilot.applicationtracker.repository.DocumentAvailabilityProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationRecordServiceTest {

    @Mock
    private ApplicationRecordRepository repository;

    @Mock
    private DocumentAvailabilityProjectionRepository availabilityRepository;

    @Mock
    private DocumentReferenceVerifier documentReferenceVerifier;

    @Mock
    private ApplicationCreationService applicationCreationService;

    @Mock
    private ApplicationEventRecorder eventRecorder;

    @Mock
    private ApplicationWithdrawalWorkflowService withdrawalWorkflowService;

    @Mock
    private ApplicationDocumentReconciliationService reconciliationService;

    @Mock
    private ApplicationAppliedFreezeService appliedFreezeService;

    private ApplicationRecordService service;

    private static final UUID CV_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COVER_LETTER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROFILE_REVISION_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVIDENCE_SNAPSHOT_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");

    @BeforeEach
    void setUp() {
        service = new ApplicationRecordService(
                repository,
                availabilityRepository,
                documentReferenceVerifier,
                applicationCreationService,
                eventRecorder,
                withdrawalWorkflowService,
                reconciliationService,
                appliedFreezeService);
    }

    @Test
    void createApplication_ShouldReturnCreatedApplication() {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID)
                .coverLetterDocumentId(COVER_LETTER_ID)
                .build();

        ApplicationRecord savedRecord = ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        savedRecord.setCvDocumentFamilyId(CV_ID.toString());
        savedRecord.setCvDocumentVersion(1);
        savedRecord.setCvDocumentContentSha256("a".repeat(64));
        savedRecord.setCoverLetterDocumentFamilyId(COVER_LETTER_ID.toString());
        savedRecord.setCoverLetterDocumentVersion(1);
        savedRecord.setCoverLetterDocumentContentSha256("b".repeat(64));
        when(applicationCreationService.createApplication(
                        "user-123",
                        null,
                        request,
                        ApplicationCommandActor.user("user-123")))
                .thenReturn(new ApplicationCreationOutcome(savedRecord, true));

        ApplicationRecordResponse response = service.createApplication("user-123", request);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("user-123");
        assertThat(response.getJobId()).isEqualTo("job-456");
        assertThat(response.getJobTitle()).isEqualTo("Java Developer");
        assertThat(response.getCompanyName()).isEqualTo("Example Ltd");
        assertThat(response.getCvDocumentId()).isEqualTo(CV_ID.toString());
        assertThat(response.getCoverLetterDocumentId())
                .isEqualTo(COVER_LETTER_ID.toString());
        assertThat(response.getCvDocumentReference().getVersion()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);

        verify(applicationCreationService)
                .createApplication(
                        "user-123",
                        null,
                        request,
                        ApplicationCommandActor.user("user-123"));
    }

    @Test
    void getApplicationById_WhenExists_ShouldReturnApplication() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .cvDocumentFamilyId(CV_ID.toString())
                .cvDocumentVersion(1)
                .cvDocumentContentSha256("a".repeat(64))
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .coverLetterDocumentFamilyId(COVER_LETTER_ID.toString())
                .coverLetterDocumentVersion(1)
                .coverLetterDocumentContentSha256("b".repeat(64))
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(repository.findByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));

        ApplicationRecordResponse response = service.getApplicationById("user-123", id);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(id);
    }

    @Test
    void getApplicationById_WhenNotExists_ShouldThrowException() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, "user-123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getApplicationById("user-123", id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Application record not found.");
    }

    @Test
    void getApplicationsForUser_ShouldReturnApplications() {
        String userId = "user-123";
        ApplicationRecord record1 = ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .jobId("job-456")
                .build();
        ApplicationRecord record2 = ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .jobId("job-789")
                .build();

        when(repository.findByUserId(userId)).thenReturn(List.of(record1, record2));

        List<ApplicationRecordResponse> responses = service.getApplicationsForUser(userId);

        assertThat(responses).hasSize(2);
    }

    @Test
    void getApplicationByDocumentId_WhenCvMatches_ShouldReturnApplication() {
        ApplicationRecord record = ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .cvDocumentFamilyId(CV_ID.toString())
                .cvDocumentVersion(1)
                .cvDocumentContentSha256("a".repeat(64))
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .coverLetterDocumentFamilyId(COVER_LETTER_ID.toString())
                .coverLetterDocumentVersion(1)
                .coverLetterDocumentContentSha256("b".repeat(64))
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(repository.findByUserIdAndDocumentId(
                        "user-123", CV_ID.toString()))
                .thenReturn(List.of(record));

        ApplicationRecordResponse response =
                service.getApplicationByDocumentId("user-123", CV_ID.toString());

        assertThat(response.getId()).isEqualTo(record.getId());
        assertThat(response.getCvDocumentId()).isEqualTo(CV_ID.toString());
    }

    @Test
    void getDocumentAssociations_ReturnsEveryDraftAndFrozenUseWithoutContent() {
        LocalDateTime frozenAt = LocalDateTime.of(2026, 8, 7, 6, 0);
        ApplicationRecord draft = ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .jobId("job-draft")
                .cvDocumentId(CV_ID.toString())
                .status(ApplicationStatus.SAVED)
                .build();
        ApplicationRecord frozen = ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .jobId("job-frozen")
                .cvDocumentId(CV_ID.toString())
                .applicationUsedCvDocumentId(CV_ID.toString())
                .applicationUsedAt(frozenAt)
                .status(ApplicationStatus.APPLIED)
                .build();
        when(repository.findByUserIdAndDocumentId(
                        "user-123", CV_ID.toString()))
                .thenReturn(List.of(draft, frozen));

        var response = service.getDocumentAssociations("user-123", CV_ID);

        assertThat(response.documentId()).isEqualTo(CV_ID);
        assertThat(response.associationCount()).isEqualTo(2);
        assertThat(response.associations())
                .extracting(association -> association.associationState())
                .containsExactly(
                        DocumentApplicationAssociationState.DRAFT_SELECTED,
                        DocumentApplicationAssociationState.FROZEN_USED);
        assertThat(response.associations().get(1).frozenAt())
                .isEqualTo(frozenAt);
    }

    @Test
    void updateStatus_ToApplied_DelegatesToAtomicFreezeCommand() {
        UUID id = UUID.randomUUID();
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("APPLIED")
                .expectedVersion(0L)
                .build();
        ApplicationRecordResponse frozen = ApplicationRecordResponse.builder()
                .id(id)
                .status(ApplicationStatus.APPLIED)
                .version(1)
                .build();
        when(appliedFreezeService.apply(
                        eq("user-123"),
                        eq(id),
                        anyString(),
                        eq(request),
                        any(ApplicationCommandActor.class)))
                .thenReturn(frozen);

        ApplicationRecordResponse response = service.updateStatus("user-123", id, request);

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        verify(appliedFreezeService).apply(
                eq("user-123"),
                eq(id),
                anyString(),
                eq(request),
                any(ApplicationCommandActor.class));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatus_ToInterview_ShouldNotOverwriteAppliedAt() {
        UUID id = UUID.randomUUID();
        LocalDateTime appliedAt = LocalDateTime.now().minusDays(2);
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("INTERVIEW")
                .build();

        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .cvDocumentFamilyId(CV_ID.toString())
                .cvDocumentVersion(1)
                .cvDocumentContentSha256("a".repeat(64))
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .coverLetterDocumentFamilyId(COVER_LETTER_ID.toString())
                .coverLetterDocumentVersion(1)
                .coverLetterDocumentContentSha256("b".repeat(64))
                .status(ApplicationStatus.APPLIED)
                .appliedAt(appliedAt)
                .createdAt(LocalDateTime.now().minusDays(3))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();

        when(repository.findByIdAndUserId(id, "user-123")).thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any(ApplicationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationRecordResponse response = service.updateStatus("user-123", id, request);

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(response.getAppliedAt()).isEqualTo(appliedAt);
    }

    @Test
    void updateStatus_ManualApplicationWithoutGeneratedDocuments_ShouldProgress() {
        UUID id = UUID.randomUUID();
        LocalDateTime appliedAt = LocalDateTime.now().minusDays(2);
        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .canonicalJobId("canonical-job-456")
                .provider("FIXTURE")
                .externalJobId("fixture-456")
                .provenance(ApplicationProvenance.MANUAL)
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(appliedAt)
                .createdAt(LocalDateTime.now().minusDays(3))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();

        when(repository.findByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any(ApplicationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationRecordResponse response = service.updateStatus(
                "user-123",
                id,
                UpdateStatusRequest.builder().status("INTERVIEW").build());

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(response.getAppliedAt()).isEqualTo(appliedAt);
        assertThat(response.getApplicationUsedCvDocumentReference()).isNull();
        assertThat(response.getApplicationUsedCoverLetterDocumentReference()).isNull();
    }

    @Test
    void replacementBeforeProgressChangesOnlyTheCurrentCanonicalReference() {
        UUID id = UUID.randomUUID();
        UUID replacementId =
                UUID.fromString("33333333-3333-4333-8333-333333333333");
        ApplicationRecord record = canonicalRecord(id);
        when(repository.findForUpdateByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(documentReferenceVerifier.verify(
                        "user-123",
                        replacementId,
                        "job-456",
                        id,
                        DocumentType.CV))
                .thenReturn(DocumentVersionReference.builder()
                        .documentId(replacementId)
                        .documentFamilyId(CV_ID)
                        .jobId("job-456")
                        .documentType(DocumentType.CV)
                        .version(2)
                        .contentSha256("c".repeat(64))
                        .build());
        when(repository.saveAndFlush(any(ApplicationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationRecordResponse response = service.updateDocumentReference(
                "user-123",
                id,
                UpdateDocumentReferenceRequest.builder()
                        .documentType("CV")
                        .documentId(replacementId)
                        .build());

        assertThat(response.getCvDocumentReference().getDocumentId())
                .isEqualTo(replacementId);
        assertThat(response.getCvDocumentReference().getVersion()).isEqualTo(2);
        assertThat(response.getApplicationUsedCvDocumentReference()).isNull();
    }

    @Test
    void savedApplicationAcceptsValidatedDocumentAttachmentWithoutChangingStatus() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = savedRecord(id);
        when(repository.findForUpdateByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(documentReferenceVerifier.verify(
                        "user-123",
                        CV_ID,
                        "job-456",
                        id,
                        DocumentType.CV))
                .thenReturn(reference(CV_ID, DocumentType.CV));
        when(repository.saveAndFlush(record)).thenReturn(record);

        ApplicationRecordResponse response = service.updateDocumentReference(
                "user-123",
                id,
                UpdateDocumentReferenceRequest.builder()
                        .documentType("CV")
                        .documentId(CV_ID)
                        .build());

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.SAVED);
        assertThat(response.getCvDocumentReference().getDocumentId())
                .isEqualTo(CV_ID);
        assertThat(response.getCoverLetterDocumentReference()).isNull();
        assertThat(response.getApplicationUsedCvDocumentReference()).isNull();
        verify(eventRecorder).recordDocumentReferenceChanged(
                any(ApplicationRecord.class),
                any(),
                any(ApplicationCommandActor.class),
                anyString());
    }

    @Test
    void savedApplicationRequiresCompletePairBeforeDocumentsGenerated() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = savedRecord(id);
        setCurrentCv(record, reference(CV_ID, DocumentType.CV));
        when(repository.findByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.updateStatus(
                        "user-123",
                        id,
                        UpdateStatusRequest.builder()
                                .status("DOCUMENTS_GENERATED")
                                .build()))
                .isInstanceOf(
                        com.jobseekercopilot.applicationtracker.exception
                                .InvalidDocumentReferenceException.class);

        assertThat(record.getStatus()).isEqualTo(ApplicationStatus.SAVED);
        assertThat(record.getAppliedAt()).isNull();
        verify(repository, never()).saveAndFlush(any());
        verify(reconciliationService, never()).requireHealthy(any());
    }

    @Test
    void savedApplicationWithCompleteHealthyPairBecomesDocumentsGenerated() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = savedRecord(id);
        setCurrentCv(record, reference(CV_ID, DocumentType.CV));
        setCurrentCoverLetter(
                record, reference(COVER_LETTER_ID, DocumentType.COVER_LETTER));
        when(repository.findByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(repository.saveAndFlush(record)).thenReturn(record);

        ApplicationRecordResponse response = service.updateStatus(
                "user-123",
                id,
                UpdateStatusRequest.builder()
                        .status("DOCUMENTS_GENERATED")
                        .build());

        assertThat(response.getStatus())
                .isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);
        assertThat(response.getAppliedAt()).isNull();
        assertThat(response.getApplicationUsedCvDocumentReference()).isNull();
        verify(reconciliationService).requireHealthy(record);
        verify(eventRecorder).recordStatusChanged(
                eq(record),
                eq(ApplicationStatus.SAVED),
                any(),
                any(ApplicationCommandActor.class),
                any());
    }

    @Test
    void appliedTransitionPassesExplicitIdempotencyKeyToFreezeService() {
        UUID id = UUID.randomUUID();
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("APPLIED")
                .expectedVersion(7L)
                .build();
        ApplicationRecordResponse frozen = ApplicationRecordResponse.builder()
                .id(id)
                .status(ApplicationStatus.APPLIED)
                .version(8)
                .build();
        when(appliedFreezeService.apply(
                        "user-123",
                        id,
                        "apply-attempt-1",
                        request,
                        ApplicationCommandActor.user("user-123")))
                .thenReturn(frozen);

        ApplicationRecordResponse response = service.updateStatus(
                "user-123",
                id,
                request,
                "apply-attempt-1",
                ApplicationCommandActor.user("user-123"));

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(response.getVersion()).isEqualTo(8);
    }

    @Test
    void optionalSingleDocumentApplyIsOwnedByFreezeService() {
        UUID id = UUID.randomUUID();
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("APPLIED")
                .expectedVersion(2L)
                .build();
        when(appliedFreezeService.apply(
                        eq("user-123"),
                        eq(id),
                        anyString(),
                        eq(request),
                        any()))
                .thenReturn(ApplicationRecordResponse.builder()
                        .id(id)
                        .status(ApplicationStatus.APPLIED)
                        .build());

        assertThat(service.updateStatus("user-123", id, request).getStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    void frozenReferencesSurviveFurtherProgressionAndRejectReplacement() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = canonicalRecord(id);
        freezeRecord(record);
        when(repository.findByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(repository.findForUpdateByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any(ApplicationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DocumentEvidenceProvenance usedProvenance =
                record.getApplicationUsedCvEvidenceProvenance();
        DocumentEvidenceProvenance laterCurrentProvenance = provenance(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                UUID.fromString("88888888-8888-4888-8888-888888888888"));
        record.setCvDocumentEvidenceProvenance(laterCurrentProvenance);
        ApplicationRecordResponse progressed = service.updateStatus(
                "user-123",
                id,
                UpdateStatusRequest.builder().status("INTERVIEW").build());

        assertThat(progressed.getApplicationUsedCvDocumentReference().getDocumentId())
                .isEqualTo(CV_ID);
        assertThat(progressed.getCvDocumentReference()
                        .getEvidenceProvenance()
                        .profileRevisionId())
                .isEqualTo(laterCurrentProvenance.profileRevisionId());
        assertThat(progressed.getApplicationUsedCvDocumentReference()
                        .getEvidenceProvenance())
                .isEqualTo(usedProvenance);
        assertThat(progressed.getApplicationUsedCvDocumentReference()
                        .getEvidenceProvenance()
                        .profileRevisionId())
                .isEqualTo(PROFILE_REVISION_ID);
        assertThat(progressed.getApplicationUsedCvDocumentReference()
                        .getEvidenceProvenance()
                        .evidenceSnapshotId())
                .isEqualTo(EVIDENCE_SNAPSHOT_ID);
        assertThatThrownBy(() -> service.updateDocumentReference(
                        "user-123",
                        id,
                        UpdateDocumentReferenceRequest.builder()
                                .documentType("CV")
                                .documentId(UUID.randomUUID())
                                .build()))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("cannot be replaced");
        verify(documentReferenceVerifier, never()).verify(
                anyString(),
                any(UUID.class),
                anyString(),
                any(UUID.class),
                any(DocumentType.class));
    }

    @Test
    void updateStatus_WithInvalidStatus_ShouldThrowException() {
        UUID id = UUID.randomUUID();
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("INVALID_STATUS")
                .build();

        assertThatThrownBy(() -> service.updateStatus("user-123", id, request))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("Invalid status");
    }

    @Test
    void deleteApplication_WhenExists_ShouldDelete() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .build();
        when(repository.findForUpdateByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));

        service.deleteApplication("user-123", id);

        verify(repository).delete(record);
    }

    @Test
    void deleteApplication_WhenNotExists_ShouldThrowException() {
        UUID id = UUID.randomUUID();
        when(repository.findForUpdateByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteApplication("user-123", id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Application record not found.");
    }

    @Test
    void deleteApplication_PreservesCoverLetterOnlyFrozenHistory() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .applicationUsedAt(LocalDateTime.now())
                .applicationUsedCoverLetterDocumentId(
                        COVER_LETTER_ID.toString())
                .build();
        when(repository.findForUpdateByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.deleteApplication("user-123", id))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("retention-aware deletion");

        verify(repository, never()).delete(any());
    }

    @Test
    void withdrawGeneratedApplication_ShouldDelegateToDurableWorkflow() {
        UUID id = UUID.randomUUID();
        WithdrawGeneratedApplicationResponse expected =
                WithdrawGeneratedApplicationResponse.builder()
                        .applicationId(id)
                        .status("NEW")
                        .withdrawn(true)
                        .build();
        when(withdrawalWorkflowService.withdraw(
                        anyString(),
                        any(UUID.class),
                        any(ApplicationCommandActor.class)))
                .thenReturn(expected);

        WithdrawGeneratedApplicationResponse response =
                service.withdrawGeneratedApplication("user-123", id, null);

        assertThat(response).isSameAs(expected);
        verify(withdrawalWorkflowService).withdraw(
                "user-123", id, ApplicationCommandActor.user("user-123"));
    }

    @Test
    void generatedWithdrawalStatus_ShouldDelegateToDurableWorkflow() {
        UUID id = UUID.randomUUID();
        WithdrawGeneratedApplicationResponse expected =
                WithdrawGeneratedApplicationResponse.builder()
                        .applicationId(id)
                        .operationStatus("RECOVERY_REQUIRED")
                        .build();
        when(withdrawalWorkflowService.status("user-123", id))
                .thenReturn(expected);

        assertThat(service.generatedWithdrawalStatus("user-123", id))
                .isSameAs(expected);
    }

    private DocumentVersionReference reference(UUID id, DocumentType type) {
        return DocumentVersionReference.builder()
                .documentId(id)
                .documentFamilyId(id)
                .jobId("job-456")
                .documentType(type)
                .version(1)
                .contentSha256(
                        type == DocumentType.CV ? "a".repeat(64) : "b".repeat(64))
                .evidenceProvenance(
                        provenance(PROFILE_REVISION_ID, EVIDENCE_SNAPSHOT_ID))
                .groundingState(
                        DocumentGroundingState
                                .AI_GENERATED_EVIDENCE_VALIDATED)
                .build();
    }

    private ApplicationRecord canonicalRecord(UUID id) {
        return ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .cvDocumentFamilyId(CV_ID.toString())
                .cvDocumentVersion(1)
                .cvDocumentContentSha256("a".repeat(64))
                .cvDocumentEvidenceProvenance(
                        provenance(PROFILE_REVISION_ID, EVIDENCE_SNAPSHOT_ID))
                .cvDocumentGroundingState(
                        DocumentGroundingState
                                .AI_GENERATED_EVIDENCE_VALIDATED)
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .coverLetterDocumentFamilyId(COVER_LETTER_ID.toString())
                .coverLetterDocumentVersion(1)
                .coverLetterDocumentContentSha256("b".repeat(64))
                .coverLetterDocumentEvidenceProvenance(
                        provenance(PROFILE_REVISION_ID, EVIDENCE_SNAPSHOT_ID))
                .coverLetterDocumentGroundingState(
                        DocumentGroundingState
                                .AI_GENERATED_EVIDENCE_VALIDATED)
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void freezeRecord(ApplicationRecord record) {
        record.setApplicationUsedCvDocumentId(record.getCvDocumentId());
        record.setApplicationUsedCvDocumentFamilyId(record.getCvDocumentFamilyId());
        record.setApplicationUsedCvDocumentVersion(record.getCvDocumentVersion());
        record.setApplicationUsedCvDocumentContentSha256(
                record.getCvDocumentContentSha256());
        record.setApplicationUsedCvEvidenceProvenance(
                record.getCvDocumentEvidenceProvenance());
        record.setApplicationUsedCvGroundingState(
                record.getCvDocumentGroundingState());
        record.setApplicationUsedCoverLetterDocumentId(
                record.getCoverLetterDocumentId());
        record.setApplicationUsedCoverLetterDocumentFamilyId(
                record.getCoverLetterDocumentFamilyId());
        record.setApplicationUsedCoverLetterDocumentVersion(
                record.getCoverLetterDocumentVersion());
        record.setApplicationUsedCoverLetterDocumentContentSha256(
                record.getCoverLetterDocumentContentSha256());
        record.setApplicationUsedCoverLetterEvidenceProvenance(
                record.getCoverLetterDocumentEvidenceProvenance());
        record.setApplicationUsedCoverLetterGroundingState(
                record.getCoverLetterDocumentGroundingState());
        record.setApplicationUsedAt(LocalDateTime.now().minusHours(1));
        record.setAppliedAt(record.getApplicationUsedAt());
        record.setStatus(ApplicationStatus.APPLIED);
    }

    private ApplicationRecord savedRecord(UUID id) {
        return ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .canonicalJobId("canonical-job-456")
                .provider("REED")
                .externalJobId("reed-456")
                .provenance(ApplicationProvenance.MANUAL)
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .status(ApplicationStatus.SAVED)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    private void setCurrentCv(
            ApplicationRecord record, DocumentVersionReference reference) {
        record.setCvDocumentId(reference.getDocumentId().toString());
        record.setCvDocumentFamilyId(
                reference.getDocumentFamilyId().toString());
        record.setCvDocumentVersion(reference.getVersion());
        record.setCvDocumentContentSha256(reference.getContentSha256());
        record.setCvDocumentEvidenceProvenance(
                reference.getEvidenceProvenance());
        record.setCvDocumentGroundingState(reference.getGroundingState());
    }

    private void setCurrentCoverLetter(
            ApplicationRecord record, DocumentVersionReference reference) {
        record.setCoverLetterDocumentId(reference.getDocumentId().toString());
        record.setCoverLetterDocumentFamilyId(
                reference.getDocumentFamilyId().toString());
        record.setCoverLetterDocumentVersion(reference.getVersion());
        record.setCoverLetterDocumentContentSha256(
                reference.getContentSha256());
        record.setCoverLetterDocumentEvidenceProvenance(
                reference.getEvidenceProvenance());
        record.setCoverLetterDocumentGroundingState(
                reference.getGroundingState());
    }

    private DocumentEvidenceProvenance provenance(
            UUID profileRevisionId, UUID snapshotId) {
        UUID evidenceId =
                UUID.fromString("55555555-5555-4555-8555-555555555555");
        return new DocumentEvidenceProvenance(
                profileRevisionId,
                "c".repeat(64),
                snapshotId,
                "d".repeat(64),
                List.of(new EvidenceRevisionReference(
                        evidenceId,
                        UUID.fromString(
                                "66666666-6666-4666-8666-666666666666"),
                        2,
                        EvidenceSection.EMPLOYMENT,
                        "e".repeat(64))),
                List.of(EvidenceSection.EMPLOYMENT),
                new ValidatedClaimLedger(
                        UUID.fromString(
                                "99999999-9999-4999-8999-999999999999"),
                        "f".repeat(64),
                        "2.0.0",
                        "3.0.0"),
                OffsetDateTime.parse("2026-07-29T03:00:00Z"));
    }
}
