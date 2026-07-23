package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationRecordServiceTest {

    @Mock
    private ApplicationRecordRepository repository;

    private ApplicationRecordService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationRecordService(repository);
    }

    @Test
    void createApplication_ShouldReturnCreatedApplication() {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .build();

        ApplicationRecord savedRecord = ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(repository.save(any(ApplicationRecord.class))).thenReturn(savedRecord);

        ApplicationRecordResponse response = service.createApplication(request);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("user-123");
        assertThat(response.getJobId()).isEqualTo("job-456");
        assertThat(response.getJobTitle()).isEqualTo("Java Developer");
        assertThat(response.getCompanyName()).isEqualTo("Example Ltd");
        assertThat(response.getCvDocumentId()).isEqualTo("cv-123");
        assertThat(response.getCoverLetterDocumentId()).isEqualTo("cl-456");
        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);

        verify(repository).save(any(ApplicationRecord.class));
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(repository.findById(id)).thenReturn(Optional.of(record));

        ApplicationRecordResponse response = service.getApplicationById(id);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(id);
    }

    @Test
    void getApplicationById_WhenNotExists_ShouldThrowException() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getApplicationById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Application not found");
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(repository.findByCvDocumentIdOrCoverLetterDocumentIdOrderByUpdatedAtDesc("cv-123", "cv-123"))
                .thenReturn(List.of(record));

        ApplicationRecordResponse response = service.getApplicationByDocumentId("cv-123");

        assertThat(response.getId()).isEqualTo(record.getId());
        assertThat(response.getCvDocumentId()).isEqualTo("cv-123");
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(repository.findById(id)).thenReturn(Optional.of(record));
        when(repository.save(any(ApplicationRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationRecordResponse response = service.updateStatus(id, request);

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(response.getCvDocumentId()).isEqualTo("cv-123");
        assertThat(response.getCoverLetterDocumentId()).isEqualTo("cl-456");
        assertThat(response.getAppliedAt()).isNotNull();
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
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(appliedAt)
                .createdAt(LocalDateTime.now().minusDays(3))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();

        when(repository.findById(id)).thenReturn(Optional.of(record));
        when(repository.save(any(ApplicationRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationRecordResponse response = service.updateStatus(id, request);

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(response.getAppliedAt()).isEqualTo(appliedAt);
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

        when(repository.findById(id)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.updateStatus(id, request))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("Invalid status");
    }

    @Test
    void deleteApplication_WhenExists_ShouldDelete() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(true);

        service.deleteApplication(id);

        verify(repository).deleteById(id);
    }

    @Test
    void deleteApplication_WhenNotExists_ShouldThrowException() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteApplication(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Application not found");
    }

    @Test
    void withdrawGeneratedApplication_WhenDocumentsGenerated_ShouldDeleteAndReturnNew() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .jobTitle("Java Developer")
                .companyName("Example Ltd")
                .cvDocumentId("cv-123")
                .coverLetterDocumentId("cl-456")
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build();

        when(repository.findById(id)).thenReturn(Optional.of(record));

        WithdrawGeneratedApplicationResponse response = service.withdrawGeneratedApplication(id);

        assertThat(response.getApplicationId()).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo("NEW");
        assertThat(response.isWithdrawn()).isTrue();
        verify(repository).delete(record);
    }

    @Test
    void withdrawGeneratedApplication_WhenAlreadyApplied_ShouldRejectAndPreserveRecord() {
        UUID id = UUID.randomUUID();
        ApplicationRecord record = ApplicationRecord.builder()
                .id(id)
                .userId("user-123")
                .jobId("job-456")
                .status(ApplicationStatus.APPLIED)
                .build();

        when(repository.findById(id)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.withdrawGeneratedApplication(id))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("only be withdrawn before applying");
        verify(repository, never()).delete(record);
    }
}
