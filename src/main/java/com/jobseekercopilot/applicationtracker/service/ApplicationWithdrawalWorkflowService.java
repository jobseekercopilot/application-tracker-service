package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflow;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflowStatus;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflowType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentWorkflowRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ApplicationWithdrawalWorkflowService {

    private static final Logger log =
            LoggerFactory.getLogger(ApplicationWithdrawalWorkflowService.class);
    private static final ApplicationDocumentWorkflowType TYPE =
            ApplicationDocumentWorkflowType.GENERATED_WITHDRAWAL;

    private final ApplicationDocumentWorkflowRepository workflowRepository;
    private final ApplicationRecordRepository applicationRepository;
    private final ApplicationEventRecorder eventRecorder;
    private final DocumentStoreWorkflowClient documentStore;
    private final PlatformTransactionManager transactionManager;

    public WithdrawGeneratedApplicationResponse withdraw(
            String ownerId,
            UUID applicationId,
            ApplicationCommandActor actor) {
        ApplicationDocumentWorkflow workflow =
                transaction().execute(status ->
                        loadOrCreate(ownerId, applicationId, actor));
        return execute(workflow.getId());
    }

    public WithdrawGeneratedApplicationResponse status(
            String ownerId, UUID applicationId) {
        ApplicationDocumentWorkflow workflow = workflowRepository
                .findByUserIdAndApplicationIdAndWorkflowType(
                        ownerId, applicationId, TYPE)
                .orElseThrow(
                        ResourceNotFoundException::applicationNotFound);
        return response(workflow);
    }

    @Scheduled(
            initialDelayString =
                    "${application-tracker.workflows.recovery-initial-delay-ms:30000}",
            fixedDelayString =
                    "${application-tracker.workflows.recovery-delay-ms:30000}")
    public void resumeRecoverableWorkflows() {
        List<ApplicationDocumentWorkflow> workflows = workflowRepository
                .findTop50ByStatusInAndRetryableTrueOrderByUpdatedAtAsc(
                        List.of(
                                ApplicationDocumentWorkflowStatus.PENDING,
                                ApplicationDocumentWorkflowStatus.RUNNING,
                                ApplicationDocumentWorkflowStatus.RECOVERY_REQUIRED));
        for (ApplicationDocumentWorkflow workflow : workflows) {
            try {
                execute(workflow.getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "Generated withdrawal recovery attempt failed operationId={} error={}",
                        workflow.getId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private ApplicationDocumentWorkflow loadOrCreate(
            String ownerId,
            UUID applicationId,
            ApplicationCommandActor actor) {
        ApplicationDocumentWorkflow existing = workflowRepository
                .findByUserIdAndApplicationIdAndWorkflowType(
                        ownerId, applicationId, TYPE)
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        ApplicationRecord application = applicationRepository
                .findForUpdateByIdAndUserId(applicationId, ownerId)
                .orElseThrow(
                        ResourceNotFoundException::applicationNotFound);
        if (application.getStatus() != ApplicationStatus.DOCUMENTS_GENERATED) {
            throw new InvalidStatusException(
                    "Generated application can only be withdrawn before applying");
        }
        if (application.getActiveDocumentWorkflowId() != null) {
            throw new InvalidStatusException(
                    "Application already has a document workflow in progress");
        }

        ApplicationDocumentWorkflow workflow =
                ApplicationDocumentWorkflow.builder()
                        .applicationId(applicationId)
                        .userId(ownerId)
                        .workflowType(TYPE)
                        .status(ApplicationDocumentWorkflowStatus.PENDING)
                        .cvDocumentId(application.getCvDocumentId())
                        .coverLetterDocumentId(
                                application.getCoverLetterDocumentId())
                        .cvCleanupRequired(
                                exclusivelyReferenced(
                                        ownerId,
                                        applicationId,
                                        application.getCvDocumentId()))
                        .coverLetterCleanupRequired(
                                exclusivelyReferenced(
                                        ownerId,
                                        applicationId,
                                        application.getCoverLetterDocumentId()))
                        .actorType(actor.actorType())
                        .actorId(actor.actorId())
                        .source(actor.source())
                        .retryable(true)
                        .build();
        workflowRepository.saveAndFlush(workflow);
        application.setActiveDocumentWorkflowId(workflow.getId());
        applicationRepository.saveAndFlush(application);
        log.info(
                "Generated withdrawal workflow accepted operationId={} applicationId={}",
                workflow.getId(),
                applicationId);
        return workflow;
    }

    private boolean exclusivelyReferenced(
            String ownerId, UUID applicationId, String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return false;
        }
        return applicationRepository
                .findByUserIdAndDocumentId(ownerId, documentId)
                .stream()
                .noneMatch(record -> !applicationId.equals(record.getId()));
    }

    private WithdrawGeneratedApplicationResponse execute(UUID workflowId) {
        ApplicationDocumentWorkflow workflow =
                transaction().execute(status -> claim(workflowId));
        if (workflow.getStatus()
                == ApplicationDocumentWorkflowStatus.COMPLETED) {
            return response(workflow);
        }

        List<UUID> cleanupIds;
        try {
            cleanupIds = cleanupIds(workflow);
        } catch (IllegalArgumentException exception) {
            return fail(
                    workflowId, "DOCUMENT_REFERENCE_INVALID", false);
        }

        try {
            documentStore.softDeleteGeneratedDocuments(
                    workflow.getUserId(),
                    workflow.getId(),
                    workflow.getApplicationId(),
                    cleanupIds);
        } catch (DocumentStoreWorkflowException exception) {
            return fail(
                    workflowId,
                    exception.code(),
                    exception.retryable());
        } catch (RuntimeException exception) {
            return fail(workflowId, "DOCUMENT_STORE_UNAVAILABLE", true);
        }

        return transaction().execute(status -> complete(workflowId));
    }

    private ApplicationDocumentWorkflow claim(UUID workflowId) {
        ApplicationDocumentWorkflow workflow = workflowRepository
                .findForUpdateById(workflowId)
                .orElseThrow(
                        ResourceNotFoundException::applicationNotFound);
        if (workflow.getStatus()
                == ApplicationDocumentWorkflowStatus.COMPLETED) {
            return workflow;
        }
        workflow.setStatus(ApplicationDocumentWorkflowStatus.RUNNING);
        workflow.setAttemptCount(workflow.getAttemptCount() + 1);
        workflow.setLastErrorCode(null);
        workflow.setRetryable(true);
        return workflowRepository.saveAndFlush(workflow);
    }

    private List<UUID> cleanupIds(
            ApplicationDocumentWorkflow workflow) {
        List<UUID> ids = new ArrayList<>(2);
        if (workflow.isCvCleanupRequired()) {
            ids.add(UUID.fromString(workflow.getCvDocumentId()));
        }
        if (workflow.isCoverLetterCleanupRequired()) {
            UUID coverLetterId =
                    UUID.fromString(workflow.getCoverLetterDocumentId());
            if (!ids.contains(coverLetterId)) {
                ids.add(coverLetterId);
            }
        }
        ids.sort(UUID::compareTo);
        return List.copyOf(ids);
    }

    private WithdrawGeneratedApplicationResponse fail(
            UUID workflowId, String code, boolean retryable) {
        ApplicationDocumentWorkflow workflow =
                transaction().execute(status -> {
                    ApplicationDocumentWorkflow locked = workflowRepository
                            .findForUpdateById(workflowId)
                            .orElseThrow(
                                    ResourceNotFoundException
                                            ::applicationNotFound);
                    if (locked.getStatus()
                            != ApplicationDocumentWorkflowStatus.COMPLETED) {
                        locked.setStatus(
                                ApplicationDocumentWorkflowStatus
                                        .RECOVERY_REQUIRED);
                        locked.setLastErrorCode(code);
                        locked.setRetryable(retryable);
                        workflowRepository.saveAndFlush(locked);
                    }
                    return locked;
                });
        log.warn(
                "Generated withdrawal requires recovery operationId={} recoveryCode={} retryable={}",
                workflowId,
                code,
                retryable);
        return response(workflow);
    }

    private WithdrawGeneratedApplicationResponse complete(UUID workflowId) {
        ApplicationDocumentWorkflow workflow = workflowRepository
                .findForUpdateById(workflowId)
                .orElseThrow(
                        ResourceNotFoundException::applicationNotFound);
        if (workflow.getStatus()
                == ApplicationDocumentWorkflowStatus.COMPLETED) {
            return response(workflow);
        }
        ApplicationRecord application = applicationRepository
                .findForUpdateByIdAndUserId(
                        workflow.getApplicationId(), workflow.getUserId())
                .orElse(null);
        if (application == null) {
            return failLocked(
                    workflow, "APPLICATION_STATE_MISSING", false);
        }
        if (application.getStatus()
                        != ApplicationStatus.DOCUMENTS_GENERATED
                || !Objects.equals(
                        application.getActiveDocumentWorkflowId(),
                        workflow.getId())
                || !Objects.equals(
                        application.getCvDocumentId(),
                        workflow.getCvDocumentId())
                || !Objects.equals(
                        application.getCoverLetterDocumentId(),
                        workflow.getCoverLetterDocumentId())) {
            return failLocked(
                    workflow, "APPLICATION_STATE_MISMATCH", false);
        }

        eventRecorder.recordGeneratedWithdrawal(
                application,
                Instant.now(),
                new ApplicationCommandActor(
                        workflow.getActorType(),
                        workflow.getActorId(),
                        workflow.getSource()));
        applicationRepository.delete(application);
        workflow.setStatus(ApplicationDocumentWorkflowStatus.COMPLETED);
        workflow.setRetryable(false);
        workflow.setLastErrorCode(null);
        workflow.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
        workflowRepository.saveAndFlush(workflow);
        log.info(
                "Generated withdrawal completed operationId={} applicationId={} attempts={}",
                workflow.getId(),
                workflow.getApplicationId(),
                workflow.getAttemptCount());
        return response(workflow);
    }

    private WithdrawGeneratedApplicationResponse failLocked(
            ApplicationDocumentWorkflow workflow,
            String code,
            boolean retryable) {
        workflow.setStatus(
                ApplicationDocumentWorkflowStatus.RECOVERY_REQUIRED);
        workflow.setLastErrorCode(code);
        workflow.setRetryable(retryable);
        workflowRepository.saveAndFlush(workflow);
        return response(workflow);
    }

    private WithdrawGeneratedApplicationResponse response(
            ApplicationDocumentWorkflow workflow) {
        boolean completed = workflow.getStatus()
                == ApplicationDocumentWorkflowStatus.COMPLETED;
        return WithdrawGeneratedApplicationResponse.builder()
                .applicationId(workflow.getApplicationId())
                .status(completed ? "NEW" : "DOCUMENTS_GENERATED")
                .withdrawn(completed)
                .operationId(workflow.getId())
                .operationStatus(workflow.getStatus().name())
                .retryable(workflow.isRetryable())
                .recoveryCode(workflow.getLastErrorCode())
                .completedAt(workflow.getCompletedAt())
                .message(completed
                        ? "Generated application withdrawal completed."
                        : "Generated application withdrawal is pending recovery.")
                .build();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }
}
