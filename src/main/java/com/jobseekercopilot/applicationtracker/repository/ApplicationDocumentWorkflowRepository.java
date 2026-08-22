package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflow;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflowStatus;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflowType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationDocumentWorkflowRepository
        extends JpaRepository<ApplicationDocumentWorkflow, UUID> {

    Optional<ApplicationDocumentWorkflow>
            findByUserIdAndApplicationIdAndWorkflowType(
                    String userId,
                    UUID applicationId,
                    ApplicationDocumentWorkflowType workflowType);

    Optional<ApplicationDocumentWorkflow> findByIdAndUserId(
            UUID id, String userId);

    Optional<ApplicationDocumentWorkflow>
            findFirstByUserIdAndApplicationIdAndWorkflowTypeAndDocumentTypeAndRequestSha256OrderByCreatedAtDesc(
                    String userId,
                    UUID applicationId,
                    ApplicationDocumentWorkflowType workflowType,
                    String documentType,
                    String requestSha256);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select workflow
            from ApplicationDocumentWorkflow workflow
            where workflow.id = :id
            """)
    Optional<ApplicationDocumentWorkflow> findForUpdateById(
            @Param("id") UUID id);

    List<ApplicationDocumentWorkflow>
            findTop50ByStatusInAndRetryableTrueOrderByUpdatedAtAsc(
                    List<ApplicationDocumentWorkflowStatus> statuses);

    List<ApplicationDocumentWorkflow>
            findTop50ByWorkflowTypeAndStatusInAndRetryableTrueAndReplacementDocumentIdIsNotNullOrderByUpdatedAtAsc(
                    ApplicationDocumentWorkflowType workflowType,
                    List<ApplicationDocumentWorkflowStatus> statuses);
}
