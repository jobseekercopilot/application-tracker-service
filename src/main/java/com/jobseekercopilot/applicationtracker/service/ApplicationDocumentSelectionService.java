package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentSelectionCommand;
import com.jobseekercopilot.applicationtracker.dto.DocumentSelectionState;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.dto.SaveDocumentSelectionsRequest;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApplicationDocumentSelectionService {

    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ApplicationDocumentSelectionTransaction transaction;
    private final DocumentReferenceVerifier documentReferenceVerifier;

    public ApplicationRecordResponse save(
            String ownerId,
            UUID applicationId,
            String requestedIdempotencyKey,
            SaveDocumentSelectionsRequest request,
            ApplicationCommandActor actor) {
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        String fingerprint = fingerprint(applicationId, request);

        Optional<ApplicationRecordResponse> replay = transaction.findReplay(
                ownerId, applicationId, idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        ApplicationRecord snapshot = transaction.findApplicationForVerification(
                ownerId, applicationId, request.getExpectedVersion());
        DocumentVersionReference cv = verify(
                ownerId,
                request.getCvSelection(),
                selectionJobId(snapshot),
                applicationId,
                DocumentType.CV);
        DocumentVersionReference coverLetter = verify(
                ownerId,
                request.getCoverLetterSelection(),
                selectionJobId(snapshot),
                applicationId,
                DocumentType.COVER_LETTER);

        try {
            return transaction.apply(
                    ownerId,
                    applicationId,
                    idempotencyKey,
                    fingerprint,
                    request.getExpectedVersion(),
                    cv,
                    coverLetter,
                    actor);
        } catch (DataIntegrityViolationException ignored) {
            return transaction.resolveConstraintRace(
                    ownerId, applicationId, idempotencyKey, fingerprint);
        }
    }

    private DocumentVersionReference verify(
            String ownerId,
            DocumentSelectionCommand selection,
            String expectedJobId,
            UUID expectedApplicationId,
            DocumentType expectedType) {
        if (selection.getState() == DocumentSelectionState.OMITTED) {
            return null;
        }
        return documentReferenceVerifier.verify(
                ownerId,
                selection.getDocumentId(),
                expectedJobId,
                expectedApplicationId,
                expectedType);
    }

    private String normalizeIdempotencyKey(String requested) {
        if (requested == null || !IDEMPOTENCY_KEY.matcher(requested).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key is required and must use 1-128 safe characters.");
        }
        return requested;
    }

    private String fingerprint(
            UUID applicationId, SaveDocumentSelectionsRequest request) {
        String canonical = String.join(
                "\n",
                applicationId.toString(),
                slotFingerprint(request.getCvSelection()),
                slotFingerprint(request.getCoverLetterSelection()),
                Long.toString(request.getExpectedVersion()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String slotFingerprint(DocumentSelectionCommand selection) {
        return selection.getState().name()
                + ":"
                + (selection.getDocumentId() == null
                        ? "-"
                        : selection.getDocumentId());
    }

    private String selectionJobId(ApplicationRecord application) {
        return application.getCanonicalJobId() == null
                        || application.getCanonicalJobId().isBlank()
                ? application.getJobId()
                : application.getCanonicalJobId();
    }
}
