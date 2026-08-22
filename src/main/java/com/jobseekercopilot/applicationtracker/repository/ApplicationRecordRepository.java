package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

@Repository
public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, UUID> {

    List<ApplicationRecord> findByUserId(String userId);

    List<ApplicationRecord> findByUserIdAndFixtureScenarioId(String userId, String fixtureScenarioId);

    Optional<ApplicationRecord> findByIdAndUserId(UUID id, String userId);

    Optional<ApplicationRecord> findByUserIdAndIdempotencyKey(
            String userId, String idempotencyKey);

    Optional<ApplicationRecord> findByUserIdAndCanonicalJobIdAndFixtureScenarioIdIsNull(
            String userId, String canonicalJobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select record
            from ApplicationRecord record
            where record.id = :id and record.userId = :userId
            """)
    Optional<ApplicationRecord> findForUpdateByIdAndUserId(
            @Param("id") UUID id,
            @Param("userId") String userId);

    void deleteByUserId(String userId);

    @Query("""
            select record
            from ApplicationRecord record
            where record.userId = :userId
              and (record.cvDocumentId = :documentId
                   or record.coverLetterDocumentId = :documentId
                   or record.applicationUsedCvDocumentId = :documentId
                   or record.applicationUsedCoverLetterDocumentId = :documentId)
            order by record.updatedAt desc
            """)
    List<ApplicationRecord> findByUserIdAndDocumentId(
            @Param("userId") String userId,
            @Param("documentId") String documentId);
}
