package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, UUID> {

    List<ApplicationRecord> findByUserId(String userId);

    void deleteByUserId(String userId);

    List<ApplicationRecord> findByCvDocumentIdOrCoverLetterDocumentIdOrderByUpdatedAtDesc(String cvDocumentId, String coverLetterDocumentId);
}
