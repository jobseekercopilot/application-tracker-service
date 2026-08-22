package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentSelectionCommand;
import com.jobseekercopilot.applicationtracker.dto.DocumentSelectionState;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.SaveDocumentSelectionsRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationDocumentSelectionServiceTest {

    @Mock
    private ApplicationDocumentSelectionTransaction transaction;

    @Mock
    private DocumentReferenceVerifier verifier;

    private ApplicationDocumentSelectionService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationDocumentSelectionService(transaction, verifier);
    }

    @Test
    void selectedVersionUsesCanonicalJobAndExactExpectedType() {
        UUID applicationId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        SaveDocumentSelectionsRequest request = request(cvId, 4);
        ApplicationRecord snapshot = ApplicationRecord.builder()
                .id(applicationId)
                .userId("owner-123")
                .jobId("provider-job")
                .canonicalJobId("canonical-job")
                .status(ApplicationStatus.SAVED)
                .version(4)
                .build();
        DocumentVersionReference reference = DocumentVersionReference.builder()
                .documentId(cvId)
                .documentFamilyId(UUID.randomUUID())
                .jobId("canonical-job")
                .documentType(DocumentType.CV)
                .version(2)
                .contentSha256("a".repeat(64))
                .build();
        ApplicationRecordResponse expected = ApplicationRecordResponse.builder()
                .id(applicationId)
                .version(5)
                .build();
        when(transaction.findReplay(
                        anyString(), any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(transaction.findApplicationForVerification(
                        "owner-123", applicationId, 4))
                .thenReturn(snapshot);
        when(verifier.verify(
                        "owner-123",
                        cvId,
                        "canonical-job",
                        applicationId,
                        DocumentType.CV))
                .thenReturn(reference);
        when(transaction.apply(
                        anyString(),
                        any(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        any(),
                        any(),
                        any()))
                .thenReturn(expected);

        assertThat(service.save(
                        "owner-123",
                        applicationId,
                        "selection-attempt-1",
                        request,
                        ApplicationCommandActor.user("owner-123")))
                .isSameAs(expected);

        verify(verifier).verify(
                "owner-123",
                cvId,
                "canonical-job",
                applicationId,
                DocumentType.CV);
        verify(verifier, never()).verify(
                anyString(),
                any(UUID.class),
                anyString(),
                any(UUID.class),
                org.mockito.ArgumentMatchers.eq(DocumentType.COVER_LETTER));
    }

    @Test
    void exactReplaySkipsReferenceVerificationAndWrite() {
        UUID applicationId = UUID.randomUUID();
        ApplicationRecordResponse original = ApplicationRecordResponse.builder()
                .id(applicationId)
                .version(7)
                .build();
        when(transaction.findReplay(
                        anyString(), any(), anyString(), anyString()))
                .thenReturn(Optional.of(original));

        assertThat(service.save(
                        "owner-123",
                        applicationId,
                        "selection-retry",
                        request(UUID.randomUUID(), 6),
                        ApplicationCommandActor.user("owner-123")))
                .isSameAs(original);

        verify(verifier, never()).verify(
                anyString(), any(), anyString(), any(), any());
        verify(transaction, never()).apply(
                anyString(), any(), anyString(), anyString(), anyLong(),
                any(), any(), any());
    }

    @Test
    void unsafeIdempotencyKeyIsRejectedBeforeReadsOrVerification() {
        assertThatThrownBy(() -> service.save(
                        "owner-123",
                        UUID.randomUUID(),
                        "contains spaces",
                        request(UUID.randomUUID(), 0),
                        ApplicationCommandActor.user("owner-123")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Idempotency-Key");

        verify(transaction, never()).findReplay(
                anyString(), any(), anyString(), anyString());
        verify(verifier, never()).verify(
                anyString(), any(), anyString(), any(), any());
    }

    private SaveDocumentSelectionsRequest request(UUID cvId, long version) {
        return SaveDocumentSelectionsRequest.builder()
                .cvSelection(DocumentSelectionCommand.builder()
                        .state(DocumentSelectionState.SELECTED)
                        .documentId(cvId)
                        .build())
                .coverLetterSelection(DocumentSelectionCommand.builder()
                        .state(DocumentSelectionState.OMITTED)
                        .build())
                .expectedVersion(version)
                .build();
    }
}
