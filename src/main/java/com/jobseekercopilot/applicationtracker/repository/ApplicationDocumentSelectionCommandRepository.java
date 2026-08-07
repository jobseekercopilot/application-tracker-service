package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentSelectionCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationDocumentSelectionCommandRepository
        extends JpaRepository<ApplicationDocumentSelectionCommand, UUID> {

    Optional<ApplicationDocumentSelectionCommand> findByUserIdAndIdempotencyKey(
            String userId, String idempotencyKey);

    boolean existsByUserIdAndApplicationId(String userId, UUID applicationId);
}
