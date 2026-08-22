package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.entity.ApplicationProvenance;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.entity.FrozenDocumentSelectionState;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ApplicationCreationService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern JOB_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}");
    private static final Pattern PROVIDER =
            Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,63}");

    private final ApplicationCreationTransaction transaction;
    private final DocumentReferenceVerifier documentReferenceVerifier;
    private final AuthoritativeJobSourcePolicy authoritativeJobSourcePolicy;
    private final Clock clock;

    public ApplicationCreationOutcome createApplication(
            String ownerId,
            String requestedIdempotencyKey,
            CreateApplicationRequest request) {
        return createApplication(
                ownerId,
                requestedIdempotencyKey,
                request,
                ApplicationCommandActor.user(ownerId));
    }

    public ApplicationCreationOutcome createApplication(
            String ownerId,
            String requestedIdempotencyKey,
            CreateApplicationRequest request,
            ApplicationCommandActor actor) {
        NormalizedCommand command = normalize(ownerId, request);
        String fingerprint = fingerprint(command);
        String idempotencyKey =
                normalizeIdempotencyKey(requestedIdempotencyKey, fingerprint);

        Optional<ApplicationRecord> replay =
                transaction.findReplayOrRejectDuplicate(
                        ownerId,
                        idempotencyKey,
                        fingerprint,
                        command.canonicalJobId());
        if (replay.isPresent()) {
            return new ApplicationCreationOutcome(replay.get(), false);
        }

        DocumentVersionReference cvReference = verifyOptional(
                ownerId, command.cvDocumentId(), command.jobId(), DocumentType.CV);
        DocumentVersionReference coverLetterReference = verifyOptional(
                ownerId,
                command.coverLetterDocumentId(),
                command.jobId(),
                DocumentType.COVER_LETTER);
        ApplicationRecord candidate = candidate(
                command,
                idempotencyKey,
                fingerprint,
                cvReference,
                coverLetterReference);

        try {
            ApplicationRecord persisted = transaction.create(candidate, actor);
            return new ApplicationCreationOutcome(
                    persisted, candidate.getId().equals(persisted.getId()));
        } catch (DataIntegrityViolationException ignored) {
            ApplicationRecord persisted = transaction.resolveConstraintRace(
                    ownerId,
                    idempotencyKey,
                    fingerprint,
                    command.canonicalJobId());
            return new ApplicationCreationOutcome(persisted, false);
        }
    }

    private NormalizedCommand normalize(
            String ownerId, CreateApplicationRequest request) {
        String jobId = normalizeId(request.getJobId(), "jobId");
        ApplicationProvenance provenance = request.getProvenance() == null
                ? ApplicationProvenance.GENERATED
                : request.getProvenance();
        ApplicationStatus status = request.getInitialStatus() == null
                ? defaultStatus(provenance)
                : request.getInitialStatus();
        requireAllowedInitialStatus(provenance, status);
        requireAllowedDocuments(
                provenance,
                request.getCvDocumentId(),
                request.getCoverLetterDocumentId());
        requireExplicitJobIdentity(provenance, request);

        String canonicalJobId = normalizeId(
                firstNonBlank(request.getCanonicalJobId(), jobId),
                "canonicalJobId");
        String provider = normalizeProvider(
                firstNonBlank(
                        request.getProvider(),
                        provenance == ApplicationProvenance.EXTERNAL
                                ? "EXTERNAL"
                                : "LEGACY"));
        String externalJobId = normalizeId(
                firstNonBlank(request.getExternalJobId(), jobId),
                "externalJobId");
        AuthoritativeJobSourcePolicy.SourceMetadata source =
                authoritativeJobSourcePolicy.normalize(
                        provider,
                        externalJobId,
                        request.getListingUrl(),
                        request.getApplyUrl(),
                        request.getAttributionLabel(),
                        request.getAttributionSourceUrl(),
                        request.getLicenceUrl(),
                        request.getDisclaimer());

        return new NormalizedCommand(
                normalizeRequired(ownerId, "ownerId", 255),
                jobId,
                canonicalJobId,
                provider,
                externalJobId,
                source.listingUrl(),
                source.applyUrl(),
                source.attributionLabel(),
                source.attributionSourceUrl(),
                source.licenceUrl(),
                source.disclaimer(),
                normalizeRequired(request.getJobTitle(), "jobTitle", 300),
                normalizeRequired(request.getCompanyName(), "companyName", 300),
                normalizeOptional(request.getLocation(), "location", 300),
                request.getCvDocumentId(),
                request.getCoverLetterDocumentId(),
                provenance,
                status);
    }

    private ApplicationStatus defaultStatus(ApplicationProvenance provenance) {
        return provenance == ApplicationProvenance.GENERATED
                ? ApplicationStatus.DOCUMENTS_GENERATED
                : ApplicationStatus.APPLIED;
    }

    private void requireAllowedInitialStatus(
            ApplicationProvenance provenance, ApplicationStatus status) {
        boolean allowed = provenance == ApplicationProvenance.GENERATED
                ? status == ApplicationStatus.DOCUMENTS_GENERATED
                        || status == ApplicationStatus.APPLIED
                : status == ApplicationStatus.SAVED
                        || status == ApplicationStatus.APPLIED;
        if (!allowed) {
            throw new InvalidRequestException(
                    "GENERATED applications may start as DOCUMENTS_GENERATED or APPLIED; MANUAL and EXTERNAL applications may start as SAVED or APPLIED.");
        }
    }

    private void requireAllowedDocuments(
            ApplicationProvenance provenance, UUID cvDocumentId, UUID coverLetterDocumentId) {
        if (provenance == ApplicationProvenance.GENERATED
                && (cvDocumentId == null || coverLetterDocumentId == null)) {
            throw new InvalidRequestException(
                    "GENERATED applications require both approved document references.");
        }
    }

    private void requireExplicitJobIdentity(
            ApplicationProvenance provenance, CreateApplicationRequest request) {
        if (provenance != ApplicationProvenance.GENERATED
                && (!StringUtils.hasText(request.getCanonicalJobId())
                        || !StringUtils.hasText(request.getProvider())
                        || !StringUtils.hasText(request.getExternalJobId()))) {
            throw new InvalidRequestException(
                    "MANUAL and EXTERNAL applications require canonicalJobId, provider and externalJobId provenance.");
        }
    }

    private DocumentVersionReference verifyOptional(
            String ownerId, UUID documentId, String jobId, DocumentType type) {
        return documentId == null
                ? null
                : documentReferenceVerifier.verify(ownerId, documentId, jobId, type);
    }

    private ApplicationRecord candidate(
            NormalizedCommand command,
            String idempotencyKey,
            String fingerprint,
            DocumentVersionReference cvReference,
            DocumentVersionReference coverLetterReference) {
        ApplicationRecord record = ApplicationRecord.builder()
                .userId(command.ownerId())
                .jobId(command.jobId())
                .canonicalJobId(command.canonicalJobId())
                .provider(command.provider())
                .externalJobId(command.externalJobId())
                .provenance(command.provenance())
                .listingUrl(command.listingUrl())
                .applyUrl(command.applyUrl())
                .attributionLabel(command.attributionLabel())
                .attributionSourceUrl(command.attributionSourceUrl())
                .licenceUrl(command.licenceUrl())
                .disclaimer(command.disclaimer())
                .idempotencyKey(idempotencyKey)
                .createRequestFingerprint(fingerprint)
                .jobTitle(command.jobTitle())
                .companyName(command.companyName())
                .location(command.location())
                .status(command.status())
                .build();
        LocalDateTime selectedAt = LocalDateTime.now(clock);
        setCurrentCvReference(record, cvReference, selectedAt);
        setCurrentCoverLetterReference(record, coverLetterReference, selectedAt);

        if (command.status() == ApplicationStatus.APPLIED) {
            LocalDateTime appliedAt = selectedAt;
            record.setAppliedAt(appliedAt);
            freezePresentReferences(record, cvReference, coverLetterReference, appliedAt);
        }
        return record;
    }

    private void setCurrentCvReference(
            ApplicationRecord record,
            DocumentVersionReference reference,
            LocalDateTime selectedAt) {
        if (reference == null) {
            return;
        }
        record.setCvDocumentId(reference.getDocumentId().toString());
        record.setCvDocumentFamilyId(reference.getDocumentFamilyId().toString());
        record.setCvDocumentVersion(reference.getVersion());
        record.setCvDocumentContentSha256(reference.getContentSha256());
        record.setCvDocumentSourceType(reference.getSourceType());
        record.setCvDocumentOriginalContentSha256(
                reference.getOriginalContentSha256());
        record.setCvDocumentSelectedAt(selectedAt);
        record.setCvDocumentEvidenceProvenance(
                reference.getEvidenceProvenance());
        record.setCvDocumentGroundingState(reference.getGroundingState());
    }

    private void setCurrentCoverLetterReference(
            ApplicationRecord record,
            DocumentVersionReference reference,
            LocalDateTime selectedAt) {
        if (reference == null) {
            return;
        }
        record.setCoverLetterDocumentId(reference.getDocumentId().toString());
        record.setCoverLetterDocumentFamilyId(
                reference.getDocumentFamilyId().toString());
        record.setCoverLetterDocumentVersion(reference.getVersion());
        record.setCoverLetterDocumentContentSha256(reference.getContentSha256());
        record.setCoverLetterDocumentSourceType(reference.getSourceType());
        record.setCoverLetterDocumentOriginalContentSha256(
                reference.getOriginalContentSha256());
        record.setCoverLetterDocumentSelectedAt(selectedAt);
        record.setCoverLetterDocumentEvidenceProvenance(
                reference.getEvidenceProvenance());
        record.setCoverLetterDocumentGroundingState(
                reference.getGroundingState());
    }

    private void freezePresentReferences(
            ApplicationRecord record,
            DocumentVersionReference cvReference,
            DocumentVersionReference coverLetterReference,
            LocalDateTime appliedAt) {
        record.setApplicationUsedCvState(cvReference == null
                ? FrozenDocumentSelectionState.OMITTED
                : FrozenDocumentSelectionState.SELECTED);
        record.setApplicationUsedCoverLetterState(coverLetterReference == null
                ? FrozenDocumentSelectionState.OMITTED
                : FrozenDocumentSelectionState.SELECTED);
        if (cvReference != null) {
            record.setApplicationUsedCvDocumentId(
                    cvReference.getDocumentId().toString());
            record.setApplicationUsedCvDocumentFamilyId(
                    cvReference.getDocumentFamilyId().toString());
            record.setApplicationUsedCvDocumentVersion(cvReference.getVersion());
            record.setApplicationUsedCvDocumentContentSha256(
                    cvReference.getContentSha256());
            record.setApplicationUsedCvDocumentSourceType(
                    cvReference.getSourceType());
            record.setApplicationUsedCvDocumentOriginalContentSha256(
                    cvReference.getOriginalContentSha256());
            record.setApplicationUsedCvDocumentSelectedAt(
                    record.getCvDocumentSelectedAt());
            record.setApplicationUsedCvEvidenceProvenance(
                    cvReference.getEvidenceProvenance());
            record.setApplicationUsedCvGroundingState(
                    cvReference.getGroundingState());
        }
        if (coverLetterReference != null) {
            record.setApplicationUsedCoverLetterDocumentId(
                    coverLetterReference.getDocumentId().toString());
            record.setApplicationUsedCoverLetterDocumentFamilyId(
                    coverLetterReference.getDocumentFamilyId().toString());
            record.setApplicationUsedCoverLetterDocumentVersion(
                    coverLetterReference.getVersion());
            record.setApplicationUsedCoverLetterDocumentContentSha256(
                    coverLetterReference.getContentSha256());
            record.setApplicationUsedCoverLetterDocumentSourceType(
                    coverLetterReference.getSourceType());
            record.setApplicationUsedCoverLetterDocumentOriginalContentSha256(
                    coverLetterReference.getOriginalContentSha256());
            record.setApplicationUsedCoverLetterDocumentSelectedAt(
                    record.getCoverLetterDocumentSelectedAt());
            record.setApplicationUsedCoverLetterEvidenceProvenance(
                    coverLetterReference.getEvidenceProvenance());
            record.setApplicationUsedCoverLetterGroundingState(
                    coverLetterReference.getGroundingState());
        }
        record.setApplicationUsedAt(appliedAt);
    }

    private String normalizeId(String value, String field) {
        String normalized = normalizeRequired(value, field, 255);
        if (!JOB_ID.matcher(normalized).matches()) {
            throw new InvalidRequestException(
                    field + " must be a stable identifier using letters, digits, '.', '_', ':', '/' or '-'.");
        }
        return normalized;
    }

    private String normalizeProvider(String value) {
        String normalized = normalizeRequired(value, "provider", 64)
                .toUpperCase(Locale.ROOT);
        if (!PROVIDER.matcher(normalized).matches()) {
            throw new InvalidRequestException(
                    "provider must use letters, digits, '_' or '-'.");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String field, int maximumLength) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidRequestException(field + " is required.");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new InvalidRequestException(
                    field + " must be at most " + maximumLength + " characters.");
        }
        return normalized;
    }

    private String normalizeOptional(String value, String field, int maximumLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return normalizeRequired(value, field, maximumLength);
    }

    private String normalizeIdempotencyKey(String value, String fingerprint) {
        if (!StringUtils.hasText(value)) {
            return "legacy-" + fingerprint;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || !IDEMPOTENCY_KEY.matcher(normalized).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key must be 1-128 safe ASCII identifier characters.");
        }
        return normalized;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private String fingerprint(NormalizedCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of(
                    command.ownerId(),
                    command.jobId(),
                    command.canonicalJobId(),
                    command.provider(),
                    command.externalJobId(),
                    command.jobTitle(),
                    command.companyName(),
                    nullToEmpty(command.listingUrl()),
                    nullToEmpty(command.applyUrl()),
                    nullToEmpty(command.attributionLabel()),
                    nullToEmpty(command.attributionSourceUrl()),
                    nullToEmpty(command.licenceUrl()),
                    nullToEmpty(command.disclaimer()),
                    nullToEmpty(command.location()),
                    command.provenance().name(),
                    command.status().name(),
                    uuid(command.cvDocumentId()),
                    uuid(command.coverLetterDocumentId()))) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(bytes.length)
                        .array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String uuid(UUID value) {
        return value == null ? "" : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record NormalizedCommand(
            String ownerId,
            String jobId,
            String canonicalJobId,
            String provider,
            String externalJobId,
            String listingUrl,
            String applyUrl,
            String attributionLabel,
            String attributionSourceUrl,
            String licenceUrl,
            String disclaimer,
            String jobTitle,
            String companyName,
            String location,
            UUID cvDocumentId,
            UUID coverLetterDocumentId,
            ApplicationProvenance provenance,
            ApplicationStatus status) {
    }
}
