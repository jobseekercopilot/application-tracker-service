package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.ApplicationVersionConflictException;
import com.jobseekercopilot.applicationtracker.exception.InvalidApplicationTransitionException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationRecordLifecycleServiceTest {

    @Mock
    private ApplicationRecordRepository repository;

    private ApplicationRecordService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationRecordService(repository);
    }

    @Test
    void invalidJumpDoesNotChangeOrSaveTheRecord() {
        ApplicationRecord record = record(ApplicationStatus.DOCUMENTS_GENERATED, 0);
        when(repository.findByIdAndUserId(record.getId(), record.getUserId()))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.updateStatus(
                        record.getUserId(),
                        record.getId(),
                        request("OFFER", 0L)))
                .isInstanceOf(InvalidApplicationTransitionException.class)
                .hasMessageContaining("DOCUMENTS_GENERATED to OFFER");

        assertThat(record.getStatus()).isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void terminalStateCannotBeReopened() {
        ApplicationRecord record = record(ApplicationStatus.ACCEPTED, 4);
        when(repository.findByIdAndUserId(record.getId(), record.getUserId()))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.updateStatus(
                        record.getUserId(),
                        record.getId(),
                        request("INTERVIEW", 4L)))
                .isInstanceOf(InvalidApplicationTransitionException.class)
                .hasMessageContaining("ACCEPTED to INTERVIEW");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void staleExpectedVersionReturnsConflictWithoutMutation() {
        ApplicationRecord record = record(ApplicationStatus.APPLIED, 3);
        LocalDateTime updatedAt = record.getUpdatedAt();
        when(repository.findByIdAndUserId(record.getId(), record.getUserId()))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.updateStatus(
                        record.getUserId(),
                        record.getId(),
                        request("INTERVIEW", 2L)))
                .isInstanceOf(ApplicationVersionConflictException.class)
                .hasMessage("Application was changed by another request. Refresh and retry.");

        assertThat(record.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(record.getUpdatedAt()).isEqualTo(updatedAt);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sameStatusRepeatIsIdempotentEvenWhenTheOriginalVersionIsStale() {
        ApplicationRecord record = record(ApplicationStatus.APPLIED, 3);
        LocalDateTime updatedAt = record.getUpdatedAt();
        when(repository.findByIdAndUserId(record.getId(), record.getUserId()))
                .thenReturn(Optional.of(record));

        ApplicationRecordResponse response = service.updateStatus(
                record.getUserId(),
                record.getId(),
                request("APPLIED", 2L));

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(response.getVersion()).isEqualTo(3);
        assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void approvedTransitionPreservesAppliedTimestampAndPersists() {
        ApplicationRecord record = record(ApplicationStatus.APPLIED, 3);
        LocalDateTime appliedAt = record.getAppliedAt();
        when(repository.findByIdAndUserId(record.getId(), record.getUserId()))
                .thenReturn(Optional.of(record));
        when(repository.saveAndFlush(record)).thenAnswer(invocation -> {
            record.setVersion(4);
            return record;
        });

        ApplicationRecordResponse response = service.updateStatus(
                record.getUserId(),
                record.getId(),
                request("OFFER", 3L));

        assertThat(response.getStatus()).isEqualTo(ApplicationStatus.OFFER);
        assertThat(response.getAppliedAt()).isEqualTo(appliedAt);
        assertThat(response.getVersion()).isEqualTo(4);
        verify(repository).saveAndFlush(record);
    }

    private UpdateStatusRequest request(String status, Long expectedVersion) {
        return UpdateStatusRequest.builder()
                .status(status)
                .expectedVersion(expectedVersion)
                .build();
    }

    private ApplicationRecord record(ApplicationStatus status, long version) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 26, 18, 0);
        return ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId("synthetic-owner")
                .jobId("synthetic-job")
                .jobTitle("Synthetic developer")
                .companyName("Example Employer")
                .cvDocumentId("synthetic-cv")
                .coverLetterDocumentId("synthetic-cover-letter")
                .status(status)
                .createdAt(now.minusDays(2))
                .updatedAt(now.minusDays(1))
                .appliedAt(status == ApplicationStatus.DOCUMENTS_GENERATED
                        ? null
                        : now.minusDays(1))
                .version(version)
                .build();
    }
}
