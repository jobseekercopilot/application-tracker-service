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
        if (repository
                .findByUserIdAndCanonicalJobIdAndFixtureScenarioIdIsNull(
                        ownerId, canonicalJobId)
                .isPresent()) {
            throw new DuplicateApplicationException();
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplicationRecord create(ApplicationRecord candidate) {
        Optional<ApplicationRecord> replay =
                repository.findByUserIdAndIdempotencyKey(
                        candidate.getUserId(), candidate.getIdempotencyKey());
        if (replay.isPresent()) {
            requireSameCommand(
                    replay.get(), candidate.getCreateRequestFingerprint());
            return replay.get();
        }
        if (repository
                .findByUserIdAndCanonicalJobIdAndFixtureScenarioIdIsNull(
                        candidate.getUserId(), candidate.getCanonicalJobId())
                .isPresent()) {
            throw new DuplicateApplicationException();
        }
        return repository.saveAndFlush(candidate);
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
        if (repository
                .findByUserIdAndCanonicalJobIdAndFixtureScenarioIdIsNull(
                        ownerId, canonicalJobId)
                .isPresent()) {
            throw new DuplicateApplicationException();
        }
        throw new ApplicationCreationConflictException(
                "Application creation conflicted with another request. Retry with the same idempotency key.");
    }

    private void requireSameCommand(
            ApplicationRecord existing, String fingerprint) {
        if (!fingerprint.equals(existing.getCreateRequestFingerprint())) {
            throw new IdempotencyConflictException();
        }
    }
}
