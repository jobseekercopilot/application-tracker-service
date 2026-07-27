package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationRecordServiceTest {

    @Mock
    private ApplicationRecordRepository repository;

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

    private ApplicationRecordService service;

    private static final UUID CV_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COVER_LETTER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeEach
    void setUp() {
        service = new ApplicationRecordService(
                repository,
                documentReferenceVerifier,
                applicationCreationService,
                eventRecorder,
                withdrawalWorkflowService,
                reconciliationService);
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
    void updateStatus_WithValidStatus_ShouldUpdate() {
        UUID id = UUID.randomUUID();
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("APPLIED")
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
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(repository.findByIdAndUserId(id, "user-123")).thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any(ApplicationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationRecordResponse response = service.updateStatus("user-123", id, request);

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(response.getCvDocumentId()).isEqualTo(CV_ID.toString());
        assertThat(response.getCoverLetterDocumentId())
                .isEqualTo(COVER_LETTER_ID.toString());
        assertThat(response.getAppliedAt()).isNotNull();
        assertThat(response.getApplicationUsedCvDocumentReference().getDocumentId())
                .isEqualTo(CV_ID);
        assertThat(response.getApplicationUsedCoverLetterDocumentReference().getDocumentId())
                .isEqualTo(COVER_LETTER_ID);
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
                        "user-123", replacementId, "job-456", DocumentType.CV))
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
    void frozenReferencesSurviveFurtherProgressionAndRejectReplacement() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = canonicalRecord(id);
        when(repository.findByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(repository.findForUpdateByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));
        when(repository.saveAndFlush(any(ApplicationRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.updateStatus(
                "user-123",
                id,
                UpdateStatusRequest.builder().status("APPLIED").build());
        ApplicationRecordResponse progressed = service.updateStatus(
                "user-123",
                id,
                UpdateStatusRequest.builder().status("INTERVIEW").build());

        assertThat(progressed.getApplicationUsedCvDocumentReference().getDocumentId())
                .isEqualTo(CV_ID);
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
                any(DocumentType.class));
    }

    @Test
    void updateStatus_WithInvalidStatus_ShouldThrowException() {
        UUID id = UUID.randomUUID();
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("INVALID_STATUS")
                .build();

        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build();

        when(repository.findByIdAndUserId(id, "user-123"))
                .thenReturn(Optional.of(record));

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
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .coverLetterDocumentFamilyId(COVER_LETTER_ID.toString())
                .coverLetterDocumentVersion(1)
                .coverLetterDocumentContentSha256("b".repeat(64))
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
