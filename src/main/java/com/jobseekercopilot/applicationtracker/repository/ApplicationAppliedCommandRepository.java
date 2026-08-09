package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationAppliedCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationAppliedCommandRepository
        extends JpaRepository<ApplicationAppliedCommand, UUID> {
    Optional<ApplicationAppliedCommand> findByUserIdAndIdempotencyKey(
            String userId, String idempotencyKey);
}
