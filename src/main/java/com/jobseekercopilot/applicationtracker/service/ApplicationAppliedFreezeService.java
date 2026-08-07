package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
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
public class ApplicationAppliedFreezeService {
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ApplicationAppliedFreezeTransaction transaction;

    public ApplicationRecordResponse apply(
            String ownerId,
            UUID applicationId,
            String requestedIdempotencyKey,
            UpdateStatusRequest request,
            ApplicationCommandActor actor) {
        if (request.getExpectedVersion() == null) {
            throw new InvalidRequestException(
                    "expectedVersion is required when applying an application.");
        }
        String idempotencyKey = normalizeKey(requestedIdempotencyKey);
        String fingerprint = fingerprint(applicationId, request);
        Optional<ApplicationRecordResponse> replay = transaction.findReplay(
                ownerId, applicationId, idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transaction.apply(
                    ownerId,
                    applicationId,
                    idempotencyKey,
                    fingerprint,
                    request,
                    actor);
        } catch (DataIntegrityViolationException ignored) {
            return transaction.resolveConstraintRace(
                    ownerId, applicationId, idempotencyKey, fingerprint);
        }
    }

    private String normalizeKey(String requested) {
        if (requested == null || !IDEMPOTENCY_KEY.matcher(requested).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key is required and must use 1-128 safe characters when applying an application.");
        }
        return requested;
    }

    private String fingerprint(UUID applicationId, UpdateStatusRequest request) {
        String canonical = String.join(
                "\n",
                applicationId.toString(),
                "APPLIED",
                Long.toString(request.getExpectedVersion()),
                request.getOccurredAt() == null
                        ? "-"
                        : request.getOccurredAt().toString(),
                request.getReason() == null
                        ? "-"
                        : request.getReason().trim());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
