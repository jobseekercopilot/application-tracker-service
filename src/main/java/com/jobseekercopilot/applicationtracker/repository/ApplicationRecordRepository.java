package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, UUID> {

    List<ApplicationRecord> findByUserId(String userId);

    List<ApplicationRecord> findByUserIdAndFixtureScenarioId(String userId, String fixtureScenarioId);

    Optional<ApplicationRecord> findByIdAndUserId(UUID id, String userId);

    @Query("""
            select record
            from ApplicationRecord record
            where record.userId = :userId
              and (record.cvDocumentId = :documentId
                   or record.coverLetterDocumentId = :documentId)
            order by record.updatedAt desc
            """)
    List<ApplicationRecord> findByUserIdAndDocumentId(
            @Param("userId") String userId,
            @Param("documentId") String documentId);
}
