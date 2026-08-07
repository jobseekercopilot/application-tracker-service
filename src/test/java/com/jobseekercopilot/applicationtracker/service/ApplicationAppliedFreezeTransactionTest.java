package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.FrozenDocumentSelectionState;
import com.jobseekercopilot.applicationtracker.exception.ApplicationSelectionVersionConflictException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationAppliedCommandRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationAppliedFreezeTransactionTest {
    @Mock private ApplicationAppliedCommandRepository commandRepository;
    @Mock private ApplicationRecordRepository applicationRepository;
    @Mock private DocumentReferenceVerifier verifier;
    @Mock private ApplicationEventRecorder eventRecorder;
    @Mock private ApplicationDocumentReconciliationService reconciliationService;

    private ApplicationAppliedFreezeTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new ApplicationAppliedFreezeTransaction(
                commandRepository,
                applicationRepository,
                verifier,
                eventRecorder,
                reconciliationService,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void freezesAllFourOptionalSelectionCombinationsAtomically() {
        UUID cvId = UUID.randomUUID();
        UUID letterId = UUID.randomUUID();
        List<List<UUID>> combinations = List.of(
                List.of(),
                List.of(cvId),
                List.of(letterId),
                List.of(cvId, letterId));

        for (List<UUID> selected : combinations) {
            reset(
                    commandRepository,
                    applicationRepository,
                    verifier,
                    eventRecorder,
                    reconciliationService);
            ApplicationRecord record = record(
                    selected.contains(cvId) ? cvId : null,
                    selected.contains(letterId) ? letterId : null,
                    5);
            when(commandRepository.findByUserIdAndIdempotencyKey(
                            "alice", "apply-" + combinations.indexOf(selected)))
                    .thenReturn(Optional.empty());
            when(applicationRepository.findForUpdateByIdAndUserId(
                            record.getId(), "alice"))
                    .thenReturn(Optional.of(record));
            if (selected.contains(cvId)) {
                when(verifier.verify(
                                "alice", cvId, "job-1", DocumentType.CV))
                        .thenReturn(reference(cvId, DocumentType.CV));
            }
            if (selected.contains(letterId)) {
                when(verifier.verify(
                                "alice",
                                letterId,
                                "job-1",
                                DocumentType.COVER_LETTER))
                        .thenReturn(reference(letterId, DocumentType.COVER_LETTER));
            }
            when(applicationRepository.saveAndFlush(record)).thenAnswer(call -> {
                record.setVersion(6);
                return record;
            });

            ApplicationRecordResponse response = transaction.apply(
                    "alice",
                    record.getId(),
                    "apply-" + combinations.indexOf(selected),
                    "a".repeat(64),
                    UpdateStatusRequest.builder()
                            .status("APPLIED")
                            .expectedVersion(5L)
                            .build(),
                    ApplicationCommandActor.user("alice"));

            assertThat(response.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
            assertThat(response.getAppliedAt())
                    .isEqualTo(response.getApplicationUsedAt());
            assertThat(response.getApplicationUsedCvState()).isEqualTo(
                    selected.contains(cvId)
                            ? FrozenDocumentSelectionState.SELECTED
                            : FrozenDocumentSelectionState.OMITTED);
            assertThat(response.getApplicationUsedCoverLetterState()).isEqualTo(
                    selected.contains(letterId)
                            ? FrozenDocumentSelectionState.SELECTED
                            : FrozenDocumentSelectionState.OMITTED);
            verify(eventRecorder).recordApplicationDocumentsFrozen(
                    eq(record),
                    eq(ApplicationStatus.SAVED),
                    any(),
                    any(),
                    any());
            verify(eventRecorder).recordStatusChanged(
                    eq(record),
                    eq(ApplicationStatus.SAVED),
                    any(),
                    any(),
                    any());
            verify(commandRepository).saveAndFlush(any());
        }
    }

    @Test
    void staleVersionFailsBeforeVerificationOrMutation() {
        ApplicationRecord record = record(UUID.randomUUID(), null, 6);
        when(commandRepository.findByUserIdAndIdempotencyKey("alice", "stale"))
                .thenReturn(Optional.empty());
        when(applicationRepository.findForUpdateByIdAndUserId(
                        record.getId(), "alice"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> transaction.apply(
                        "alice",
                        record.getId(),
                        "stale",
                        "b".repeat(64),
                        UpdateStatusRequest.builder()
                                .status("APPLIED")
                                .expectedVersion(5L)
                                .build(),
                        ApplicationCommandActor.user("alice")))
                .isInstanceOf(ApplicationSelectionVersionConflictException.class);

        assertThat(record.getStatus()).isEqualTo(ApplicationStatus.SAVED);
        assertThat(record.getApplicationUsedAt()).isNull();
        verify(verifier, never()).verify(any(), any(), any(), any());
        verify(applicationRepository, never()).saveAndFlush(any());
    }

    private ApplicationRecord record(UUID cvId, UUID letterId, long version) {
        return ApplicationRecord.builder()
                .id(UUID.randomUUID())
                .userId("alice")
                .jobId("job-1")
                .jobTitle("Engineer")
                .companyName("Example")
                .cvDocumentId(cvId == null ? null : cvId.toString())
                .cvDocumentFamilyId(cvId == null ? null : UUID.randomUUID().toString())
                .cvDocumentVersion(cvId == null ? null : 2)
                .cvDocumentContentSha256(cvId == null ? null : "c".repeat(64))
                .coverLetterDocumentId(letterId == null ? null : letterId.toString())
                .coverLetterDocumentFamilyId(
                        letterId == null ? null : UUID.randomUUID().toString())
                .coverLetterDocumentVersion(letterId == null ? null : 2)
                .coverLetterDocumentContentSha256(
                        letterId == null ? null : "d".repeat(64))
                .status(ApplicationStatus.SAVED)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusHours(1))
                .version(version)
                .build();
    }

    private DocumentVersionReference reference(UUID id, DocumentType type) {
        return DocumentVersionReference.builder()
                .documentId(id)
                .documentFamilyId(UUID.randomUUID())
                .jobId("job-1")
                .documentType(type)
                .version(2)
                .contentSha256("c".repeat(64))
                .build();
    }
}
