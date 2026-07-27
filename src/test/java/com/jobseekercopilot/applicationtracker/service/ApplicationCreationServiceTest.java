package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ApplicationCreationServiceTest {

    private static final UUID CV_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COVER_LETTER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock
    private ApplicationCreationTransaction transaction;

    @Mock
    private DocumentReferenceVerifier documentReferenceVerifier;

    private ApplicationCreationService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationCreationService(
                transaction, documentReferenceVerifier);
    }

    @Test
    void generatedLegacyCommandGetsDeterministicIdempotencyAndApprovedDocuments() {
        CreateApplicationRequest request = generatedRequest();
        when(transaction.findReplayOrRejectDuplicate(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(documentReferenceVerifier.verify(
                        "owner-1", CV_ID, "job-1", DocumentType.CV))
                .thenReturn(reference(CV_ID, DocumentType.CV));
        when(documentReferenceVerifier.verify(
                        "owner-1",
                        COVER_LETTER_ID,
                        "job-1",
                        DocumentType.COVER_LETTER))
                .thenReturn(reference(COVER_LETTER_ID, DocumentType.COVER_LETTER));
        when(transaction.create(
                        any(ApplicationRecord.class),
                        any(ApplicationCommandActor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationCreationOutcome outcome =
                service.createApplication("owner-1", null, request);

        ArgumentCaptor<ApplicationRecord> candidate =
                ArgumentCaptor.forClass(ApplicationRecord.class);
        verify(transaction).create(
                candidate.capture(), any(ApplicationCommandActor.class));
        assertThat(outcome.created()).isTrue();
        assertThat(candidate.getValue().getIdempotencyKey())
                .startsWith("legacy-")
                .hasSize(71);
        assertThat(candidate.getValue().getCreateRequestFingerprint())
                .matches("[0-9a-f]{64}");
        assertThat(candidate.getValue().getCanonicalJobId()).isEqualTo("job-1");
        assertThat(candidate.getValue().getProvider()).isEqualTo("LEGACY");
        assertThat(candidate.getValue().getExternalJobId()).isEqualTo("job-1");
        assertThat(candidate.getValue().getProvenance())
                .isEqualTo(ApplicationProvenance.GENERATED);
        assertThat(candidate.getValue().getStatus())
                .isEqualTo(ApplicationStatus.DOCUMENTS_GENERATED);
        assertThat(candidate.getValue().getCvDocumentVersion()).isEqualTo(1);
        assertThat(candidate.getValue().getApplicationUsedAt()).isNull();
    }

    @Test
    void manualApplicationCanStartAppliedWithoutDocuments() {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("owner-1")
                .jobId("manual-job-1")
                .canonicalJobId("manual-job-1")
                .provider("manual")
                .externalJobId("manual-job-1")
                .jobTitle("Support Engineer")
                .companyName("Example Ltd")
                .provenance(ApplicationProvenance.MANUAL)
                .build();
        when(transaction.findReplayOrRejectDuplicate(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(transaction.create(
                        any(ApplicationRecord.class),
                        any(ApplicationCommandActor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationCreationOutcome outcome =
                service.createApplication("owner-1", "manual-attempt-1", request);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.record().getProvenance())
                .isEqualTo(ApplicationProvenance.MANUAL);
        assertThat(outcome.record().getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(outcome.record().getAppliedAt()).isNotNull();
        assertThat(outcome.record().getCvDocumentId()).isNull();
        assertThat(outcome.record().getCoverLetterDocumentId()).isNull();
        assertThat(outcome.record().getApplicationUsedAt()).isNull();
        assertThat(outcome.record().getProvider()).isEqualTo("MANUAL");
        verify(documentReferenceVerifier, never()).verify(
                anyString(), any(UUID.class), anyString(), any(DocumentType.class));
    }

    @Test
    void externalApplicationFreezesOnlyTheApprovedDocumentThatWasSupplied() {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("owner-1")
                .jobId("external-job-1")
                .canonicalJobId("external-job-1")
                .provider("external")
                .externalJobId("external-job-1")
                .jobTitle("Platform Engineer")
                .companyName("Example Ltd")
                .provenance(ApplicationProvenance.EXTERNAL)
                .cvDocumentId(CV_ID)
                .build();
        when(transaction.findReplayOrRejectDuplicate(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(documentReferenceVerifier.verify(
                        "owner-1", CV_ID, "external-job-1", DocumentType.CV))
                .thenReturn(reference(CV_ID, DocumentType.CV, "external-job-1"));
        when(transaction.create(
                        any(ApplicationRecord.class),
                        any(ApplicationCommandActor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationCreationOutcome outcome =
                service.createApplication("owner-1", "external-attempt-1", request);

        assertThat(outcome.record().getApplicationUsedCvDocumentId())
                .isEqualTo(CV_ID.toString());
        assertThat(outcome.record().getApplicationUsedCoverLetterDocumentId())
                .isNull();
        assertThat(outcome.record().getApplicationUsedAt()).isNotNull();
    }

    @Test
    void generatedApplicationCanStartAppliedAndFreezesBothApprovedDocuments() {
        CreateApplicationRequest request = generatedRequest();
        request.setInitialStatus(ApplicationStatus.APPLIED);
        when(transaction.findReplayOrRejectDuplicate(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(documentReferenceVerifier.verify(
                        anyString(), any(UUID.class), anyString(), any(DocumentType.class)))
                .thenAnswer(invocation -> reference(
                        invocation.getArgument(1),
                        invocation.getArgument(3),
                        invocation.getArgument(2)));
        when(transaction.create(
                        any(ApplicationRecord.class),
                        any(ApplicationCommandActor.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApplicationCreationOutcome outcome =
                service.createApplication("owner-1", "applied-attempt", request);

        assertThat(outcome.record().getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(outcome.record().getAppliedAt()).isNotNull();
        assertThat(outcome.record().getApplicationUsedCvDocumentId())
                .isEqualTo(CV_ID.toString());
        assertThat(outcome.record().getApplicationUsedCoverLetterDocumentId())
                .isEqualTo(COVER_LETTER_ID.toString());
        assertThat(outcome.record().getApplicationUsedAt())
                .isEqualTo(outcome.record().getAppliedAt());
    }

    @Test
    void replayReturnsBeforeDocumentStoreValidation() {
        ApplicationRecord existing = ApplicationRecord.builder()
                .userId("owner-1")
                .jobId("job-1")
                .canonicalJobId("job-1")
                .provider("LEGACY")
                .externalJobId("job-1")
                .jobTitle("Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID.toString())
                .coverLetterDocumentId(COVER_LETTER_ID.toString())
                .provenance(ApplicationProvenance.GENERATED)
                .status(ApplicationStatus.DOCUMENTS_GENERATED)
                .build();
        when(transaction.findReplayOrRejectDuplicate(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(existing));

        ApplicationCreationOutcome outcome =
                service.createApplication("owner-1", "attempt-1", generatedRequest());

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.record()).isSameAs(existing);
        verify(documentReferenceVerifier, never()).verify(
                anyString(), any(UUID.class), anyString(), any(DocumentType.class));
        verify(transaction, never()).create(
                any(ApplicationRecord.class),
                any(ApplicationCommandActor.class));
    }

    @Test
    void constraintRaceResolvesToTheCommittedReplay() {
        ApplicationRecord committed = ApplicationRecord.builder()
                .userId("owner-1")
                .jobId("job-1")
                .canonicalJobId("job-1")
                .build();
        when(transaction.findReplayOrRejectDuplicate(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(documentReferenceVerifier.verify(
                        anyString(), any(UUID.class), anyString(), any(DocumentType.class)))
                .thenAnswer(invocation -> reference(
                        invocation.getArgument(1),
                        invocation.getArgument(3),
                        invocation.getArgument(2)));
        when(transaction.create(
                        any(ApplicationRecord.class),
                        any(ApplicationCommandActor.class)))
                .thenThrow(new DataIntegrityViolationException("synthetic race"));
        when(transaction.resolveConstraintRace(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(committed);

        ApplicationCreationOutcome outcome =
                service.createApplication("owner-1", "attempt-1", generatedRequest());

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.record()).isSameAs(committed);
    }

    @Test
    void generatedApplicationsRejectMissingDocuments() {
        CreateApplicationRequest request = generatedRequest();
        request.setCvDocumentId(null);

        assertThatThrownBy(() ->
                service.createApplication("owner-1", "attempt-1", request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("GENERATED applications require both approved document references.");
        verify(transaction, never()).findReplayOrRejectDuplicate(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void manualApplicationsRejectGeneratedOnlyInitialState() {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("owner-1")
                .jobId("manual-job-1")
                .jobTitle("Developer")
                .companyName("Example Ltd")
                .provenance(ApplicationProvenance.MANUAL)
                .initialStatus(ApplicationStatus.DOCUMENTS_GENERATED)
                .build();

        assertThatThrownBy(() ->
                service.createApplication("owner-1", "attempt-1", request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("MANUAL and EXTERNAL");
    }

    @Test
    void manualApplicationsRejectUnattributedJobSnapshots() {
        CreateApplicationRequest request = CreateApplicationRequest.builder()
                .userId("owner-1")
                .jobId("manual-job-1")
                .jobTitle("Developer")
                .companyName("Example Ltd")
                .provenance(ApplicationProvenance.MANUAL)
                .build();

        assertThatThrownBy(() ->
                service.createApplication("owner-1", "attempt-1", request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining(
                        "canonicalJobId, provider and externalJobId provenance");
    }

    @Test
    void unsafeIdempotencyKeyIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.createApplication(
                        "owner-1", "contains spaces", generatedRequest()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Idempotency-Key");
        verify(transaction, never()).findReplayOrRejectDuplicate(
                anyString(), anyString(), anyString(), anyString());
    }

    private CreateApplicationRequest generatedRequest() {
        return CreateApplicationRequest.builder()
                .userId("owner-1")
                .jobId("job-1")
                .jobTitle("Developer")
                .companyName("Example Ltd")
                .cvDocumentId(CV_ID)
                .coverLetterDocumentId(COVER_LETTER_ID)
                .build();
    }

    private DocumentVersionReference reference(UUID id, DocumentType type) {
        return reference(id, type, "job-1");
    }

    private DocumentVersionReference reference(
            UUID id, DocumentType type, String jobId) {
        return DocumentVersionReference.builder()
                .documentId(id)
                .documentFamilyId(id)
                .jobId(jobId)
                .documentType(type)
                .version(1)
                .contentSha256(
                        type == DocumentType.CV ? "a".repeat(64) : "b".repeat(64))
                .build();
    }
}
