package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.exception.DuplicateApplicationException;
import com.jobseekercopilot.applicationtracker.exception.IdempotencyConflictException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationCreationTransactionTest {

    private static final String OWNER = "owner-1";
    private static final String KEY = "attempt-1";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String CANONICAL_JOB = "canonical-job-1";

    @Mock private ApplicationRecordRepository repository;
    @Mock private ApplicationEventRecorder eventRecorder;
    @Mock private ApplicationDocumentReconciliationService reconciliationService;

    private ApplicationCreationTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new ApplicationCreationTransaction(
                repository, eventRecorder, reconciliationService);
    }

    @Test
    void preflightReturnsSameCommandThatBecomesVisibleBetweenQueries() {
        ApplicationRecord committed = committed(KEY, FINGERPRINT);
        missIdempotencyThenFindCanonical(committed);

        Optional<ApplicationRecord> replay =
                transaction.findReplayOrRejectDuplicate(
                        OWNER, KEY, FINGERPRINT, CANONICAL_JOB);

        assertThat(replay).containsSame(committed);
    }

    @Test
    void createReturnsSameCommandThatBecomesVisibleBetweenQueries() {
        ApplicationRecord committed = committed(KEY, FINGERPRINT);
        ApplicationRecord candidate = committed(KEY, FINGERPRINT);
        missIdempotencyThenFindCanonical(committed);

        ApplicationRecord replay = transaction.create(
                candidate, ApplicationCommandActor.user(OWNER));

        assertThat(replay).isSameAs(committed);
        verify(repository, never()).saveAndFlush(candidate);
        verify(eventRecorder, never()).recordCreated(
                candidate, ApplicationCommandActor.user(OWNER));
        verify(reconciliationService, never()).markHealthy(candidate);
    }

    @Test
    void constraintResolutionReturnsCanonicalReplayAfterInitialMiss() {
        ApplicationRecord committed = committed(KEY, FINGERPRINT);
        missIdempotencyThenFindCanonical(committed);

        ApplicationRecord replay = transaction.resolveConstraintRace(
                OWNER, KEY, FINGERPRINT, CANONICAL_JOB);

        assertThat(replay).isSameAs(committed);
    }

    @Test
    void canonicalRecordWithDifferentKeyRemainsADuplicate() {
        missIdempotencyThenFindCanonical(committed("other-attempt", FINGERPRINT));

        assertThatThrownBy(() -> transaction.findReplayOrRejectDuplicate(
                        OWNER, KEY, FINGERPRINT, CANONICAL_JOB))
                .isInstanceOf(DuplicateApplicationException.class);
    }

    @Test
    void canonicalRecordWithSameKeyAndDifferentCommandRemainsAConflict() {
        missIdempotencyThenFindCanonical(committed(KEY, "b".repeat(64)));

        assertThatThrownBy(() -> transaction.findReplayOrRejectDuplicate(
                        OWNER, KEY, FINGERPRINT, CANONICAL_JOB))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private void missIdempotencyThenFindCanonical(ApplicationRecord committed) {
        when(repository.findByUserIdAndIdempotencyKey(OWNER, KEY))
                .thenReturn(Optional.empty());
        when(repository
                        .findByUserIdAndCanonicalJobIdAndFixtureScenarioIdIsNull(
                                OWNER, CANONICAL_JOB))
                .thenReturn(Optional.of(committed));
    }

    private ApplicationRecord committed(String key, String fingerprint) {
        return ApplicationRecord.builder()
                .userId(OWNER)
                .jobId(CANONICAL_JOB)
                .canonicalJobId(CANONICAL_JOB)
                .idempotencyKey(key)
                .createRequestFingerprint(fingerprint)
                .jobTitle("Platform Engineer")
                .companyName("Example Employer")
                .build();
    }
}
