package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.exception.ApplicationCreationConflictException;
import com.jobseekercopilot.applicationtracker.exception.DuplicateApplicationException;
import com.jobseekercopilot.applicationtracker.exception.IdempotencyConflictException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ApplicationCreationTransaction {

    private final ApplicationRecordRepository repository;
    private final ApplicationEventRecorder eventRecorder;
    private final ApplicationDocumentReconciliationService
            reconciliationService;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ApplicationRecord> findReplayOrRejectDuplicate(
            String ownerId,
            String idempotencyKey,
            String fingerprint,
            String canonicalJobId) {
        Optional<ApplicationRecord> replay =
                repository.findByUserIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (replay.isPresent()) {
            requireSameCommand(replay.get(), fingerprint);
            return replay;
        }
        return findCanonicalReplayOrRejectDuplicate(
                ownerId, idempotencyKey, fingerprint, canonicalJobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplicationRecord create(
            ApplicationRecord candidate, ApplicationCommandActor actor) {
        Optional<ApplicationRecord> replay =
                repository.findByUserIdAndIdempotencyKey(
                        candidate.getUserId(), candidate.getIdempotencyKey());
        if (replay.isPresent()) {
            requireSameCommand(
                    replay.get(), candidate.getCreateRequestFingerprint());
            return replay.get();
        }
        Optional<ApplicationRecord> canonicalReplay =
                findCanonicalReplayOrRejectDuplicate(
                        candidate.getUserId(),
                        candidate.getIdempotencyKey(),
                        candidate.getCreateRequestFingerprint(),
                        candidate.getCanonicalJobId());
        if (canonicalReplay.isPresent()) {
            return canonicalReplay.get();
        }
        ApplicationRecord created = repository.saveAndFlush(candidate);
        eventRecorder.recordCreated(created, actor);
        reconciliationService.markHealthy(created);
        return created;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ApplicationRecord resolveConstraintRace(
            String ownerId,
            String idempotencyKey,
            String fingerprint,
            String canonicalJobId) {
        Optional<ApplicationRecord> replay =
                repository.findByUserIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (replay.isPresent()) {
            requireSameCommand(replay.get(), fingerprint);
            return replay.get();
        }
        return findCanonicalReplayOrRejectDuplicate(
                        ownerId, idempotencyKey, fingerprint, canonicalJobId)
                .orElseThrow(() -> new ApplicationCreationConflictException(
                        "Application creation conflicted with another request. Retry with the same idempotency key."));
    }

    private Optional<ApplicationRecord> findCanonicalReplayOrRejectDuplicate(
            String ownerId,
            String idempotencyKey,
            String fingerprint,
            String canonicalJobId) {
        Optional<ApplicationRecord> canonical = repository
                .findByUserIdAndCanonicalJobIdAndFixtureScenarioIdIsNull(
                        ownerId, canonicalJobId);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        ApplicationRecord existing = canonical.get();
        if (!idempotencyKey.equals(existing.getIdempotencyKey())) {
            throw new DuplicateApplicationException();
        }
        requireSameCommand(existing, fingerprint);
        return canonical;
    }

    private void requireSameCommand(
            ApplicationRecord existing, String fingerprint) {
        if (!fingerprint.equals(existing.getCreateRequestFingerprint())) {
            throw new IdempotencyConflictException();
        }
    }
}
