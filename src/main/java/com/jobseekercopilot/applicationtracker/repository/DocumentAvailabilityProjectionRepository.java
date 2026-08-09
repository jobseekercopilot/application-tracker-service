package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.DocumentAvailabilityProjection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentAvailabilityProjectionRepository
        extends JpaRepository<DocumentAvailabilityProjection, UUID> {
    Optional<DocumentAvailabilityProjection> findByOwnerIdAndDocumentId(
            String ownerId,
            UUID documentId);

    List<DocumentAvailabilityProjection> findByOwnerIdAndDocumentIdIn(
            String ownerId,
            Collection<UUID> documentIds);
}
