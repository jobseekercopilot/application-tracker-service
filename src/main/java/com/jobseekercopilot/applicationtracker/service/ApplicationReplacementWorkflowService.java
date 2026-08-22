package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.BeginDocumentReplacementRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentReplacementWorkflowResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflow;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflowStatus;
import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentWorkflowType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceUnavailableException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import com.jobseekercopilot.applicationtracker.exception.InvalidStatusException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationDocumentWorkflowRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
public class ApplicationReplacementWorkflowService {

    private static final Logger log =
            LoggerFactory.getLogger(ApplicationReplacementWorkflowService.class);
    private static final ApplicationDocumentWorkflowType TYPE =
            ApplicationDocumentWorkflowType.DOCUMENT_REPLACEMENT;

    private final ApplicationDocumentWorkflowRepository workflowRepository;
    private final ApplicationRecordRepository applicationRepository;
    private final DocumentReferenceVerifier documentReferenceVerifier;
    private final ApplicationEventRecorder eventRecorder;
    private final ApplicationDocumentReconciliationService
            reconciliationService;
    private final PlatformTransactionManager transactionManager;

    public DocumentReplacementWorkflowResponse begin(
            String ownerId,
            UUID applicationId,
            BeginDocumentReplacementRequest request,
            ApplicationCommandActor actor) {
        return transaction().execute(status ->
                beginLocked(ownerId, applicationId, request, actor));
    }

    public DocumentReplacementWorkflowResponse registerReplacement(
            String ownerId,
            UUID applicationId,
            UUID operationId,
            UUID replacementDocumentId) {
        return transaction().execute(status -> {
            ApplicationDocumentWorkflow workflow =
                    ownedWorkflowForUpdate(ownerId, applicationId, operationId);
            if (workflow.getStatus()
                    == ApplicationDocumentWorkflowStatus.COMPLETED) {
                if (!Objects.equals(
                        workflow.getReplacementDocumentId(),
                        replacementDocumentId.toString())) {
                    throw new InvalidStatusException(
                            "Replacement workflow already completed with a different document");
                }
                return response(
                        workflow,
                        ownedApplication(ownerId, applicationId));
            }
            if (workflow.getReplacementDocumentId() != null
                    && !workflow.getReplacementDocumentId()
                            .equals(replacementDocumentId.toString())) {
                throw new InvalidStatusException(
                        "Replacement workflow already registered a different document");
            }
            workflow.setReplacementDocumentId(
                    replacementDocumentId.toString());
            workflow.setStatus(ApplicationDocumentWorkflowStatus.RUNNING);
            workflow.setRetryable(true);
            workflow.setLastErrorCode(null);
            workflowRepository.saveAndFlush(workflow);
            return response(
                    workflow,
                    ownedApplication(ownerId, applicationId));
        });
    }

    public DocumentReplacementWorkflowResponse markRecoveryRequired(
            String ownerId,
            UUID applicationId,
            UUID operationId,
            String recoveryCode,
            boolean retryable) {
        return transaction().execute(status -> {
            ApplicationDocumentWorkflow workflow =
                    ownedWorkflowForUpdate(ownerId, applicationId, operationId);
            if (workflow.getStatus()
                    != ApplicationDocumentWorkflowStatus.COMPLETED) {
                workflow.setStatus(
                        ApplicationDocumentWorkflowStatus.RECOVERY_REQUIRED);
                workflow.setLastErrorCode(normalizeRecoveryCode(recoveryCode));
                workflow.setRetryable(retryable);
                workflowRepository.saveAndFlush(workflow);
            }
            return response(
                    workflow,
                    ownedApplication(ownerId, applicationId));
        });
    }

    public DocumentReplacementWorkflowResponse complete(
            String ownerId,
            UUID applicationId,
            UUID operationId) {
        ApplicationDocumentWorkflow workflow = ownedWorkflow(
                ownerId, applicationId, operationId);
        if (workflow.getStatus()
                == ApplicationDocumentWorkflowStatus.COMPLETED) {
            return response(
                    workflow,
                    ownedApplication(ownerId, applicationId));
        }
        if (workflow.getReplacementDocumentId() == null) {
            throw new InvalidStatusException(
                    "Replacement document has not been registered");
        }

        DocumentType documentType =
                DocumentType.valueOf(workflow.getDocumentType());
        ApplicationRecord application =
                ownedApplication(ownerId, applicationId);
        DocumentVersionReference reference = documentReferenceVerifier.verify(
                ownerId,
                UUID.fromString(workflow.getReplacementDocumentId()),
                application.getJobId(),
                documentType);
        return transaction().execute(status ->
                completeLocked(
                        ownerId,
                        applicationId,
                        operationId,
                        reference));
    }

    public DocumentReplacementWorkflowResponse status(
            String ownerId,
            UUID applicationId,
            UUID operationId) {
        return response(
                ownedWorkflow(ownerId, applicationId, operationId),
                ownedApplication(ownerId, applicationId));
    }

    @Scheduled(
            initialDelayString =
                    "${application-tracker.workflows.recovery-initial-delay-ms:30000}",
            fixedDelayString =
                    "${application-tracker.workflows.recovery-delay-ms:30000}")
    public void reconcileRegisteredReplacements() {
        List<ApplicationDocumentWorkflow> workflows = workflowRepository
                .findTop50ByWorkflowTypeAndStatusInAndRetryableTrueAndReplacementDocumentIdIsNotNullOrderByUpdatedAtAsc(
                        TYPE,
                        List.of(
                                ApplicationDocumentWorkflowStatus.PENDING,
                                ApplicationDocumentWorkflowStatus.RUNNING,
                                ApplicationDocumentWorkflowStatus.RECOVERY_REQUIRED));
        for (ApplicationDocumentWorkflow workflow : workflows) {
            try {
                complete(
                        workflow.getUserId(),
                        workflow.getApplicationId(),
                        workflow.getId());
            } catch (InvalidDocumentReferenceException exception) {
                markRecoveryRequired(
                        workflow.getUserId(),
                        workflow.getApplicationId(),
                        workflow.getId(),
                        "REPLACEMENT_NOT_READY",
                        true);
            } catch (DocumentReferenceUnavailableException exception) {
                markRecoveryRequired(
                        workflow.getUserId(),
                        workflow.getApplicationId(),
                        workflow.getId(),
                        "DOCUMENT_STORE_UNAVAILABLE",
                        true);
            } catch (RuntimeException exception) {
                log.warn(
                        "Document replacement reconciliation failed operationId={} error={}",
                        workflow.getId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private DocumentReplacementWorkflowResponse beginLocked(
            String ownerId,
            UUID applicationId,
            BeginDocumentReplacementRequest request,
            ApplicationCommandActor actor) {
        ApplicationRecord application = applicationRepository
                .findForUpdateByIdAndUserId(applicationId, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        requireReplaceable(application);
        String documentType = request.getDocumentType();
        String currentDocumentId =
                currentDocumentId(application, documentType);
        requireUuid(currentDocumentId);

        if (application.getActiveDocumentWorkflowId() != null) {
            ApplicationDocumentWorkflow active = workflowRepository
                    .findForUpdateById(
                            application.getActiveDocumentWorkflowId())
                    .orElseThrow(() -> new InvalidStatusException(
                            "Application document workflow state is missing"));
            requireMatchingBegin(
                    active,
                    ownerId,
                    applicationId,
                    documentType,
                    request.getRequestSha256());
            if (active.getStatus()
                    != ApplicationDocumentWorkflowStatus.COMPLETED) {
                active.setStatus(ApplicationDocumentWorkflowStatus.PENDING);
                active.setRetryable(true);
                active.setLastErrorCode(null);
                active.setAttemptCount(active.getAttemptCount() + 1);
                workflowRepository.saveAndFlush(active);
            }
            return response(active, application);
        }

        ApplicationDocumentWorkflow completed = workflowRepository
                .findFirstByUserIdAndApplicationIdAndWorkflowTypeAndDocumentTypeAndRequestSha256OrderByCreatedAtDesc(
                        ownerId,
                        applicationId,
                        TYPE,
                        documentType,
                        request.getRequestSha256())
                .filter(item ->
                        item.getStatus()
                                == ApplicationDocumentWorkflowStatus.COMPLETED)
                .filter(item -> Objects.equals(
                        currentDocumentId,
                        item.getReplacementDocumentId()))
                .orElse(null);
        if (completed != null) {
            return response(completed, application);
        }

        ApplicationDocumentWorkflow workflow =
                ApplicationDocumentWorkflow.builder()
                        .applicationId(applicationId)
                        .userId(ownerId)
                        .workflowType(TYPE)
                        .status(ApplicationDocumentWorkflowStatus.PENDING)
                        .documentType(documentType)
                        .sourceDocumentId(currentDocumentId)
                        .requestSha256(request.getRequestSha256())
                        .actorType(actor.actorType())
                        .actorId(actor.actorId())
                        .source(actor.source())
                        .attemptCount(1)
                        .retryable(true)
                        .build();
        workflowRepository.saveAndFlush(workflow);
        application.setActiveDocumentWorkflowId(workflow.getId());
        applicationRepository.saveAndFlush(application);
        log.info(
                "Document replacement workflow accepted operationId={} documentType={}",
                workflow.getId(),
                documentType);
        return response(workflow, application);
    }

    private DocumentReplacementWorkflowResponse completeLocked(
            String ownerId,
            UUID applicationId,
            UUID operationId,
            DocumentVersionReference reference) {
        ApplicationDocumentWorkflow workflow =
                ownedWorkflowForUpdate(ownerId, applicationId, operationId);
        ApplicationRecord application = applicationRepository
                .findForUpdateByIdAndUserId(applicationId, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        if (workflow.getStatus()
                == ApplicationDocumentWorkflowStatus.COMPLETED) {
            return response(workflow, application);
        }
        requireReplaceable(application);
        if (!Objects.equals(
                        application.getActiveDocumentWorkflowId(),
                        workflow.getId())
                || !Objects.equals(
                        currentDocumentId(
                                application, workflow.getDocumentType()),
                        workflow.getSourceDocumentId())
                || !Objects.equals(
                        reference.getDocumentId().toString(),
                        workflow.getReplacementDocumentId())) {
            workflow.setStatus(
                    ApplicationDocumentWorkflowStatus.RECOVERY_REQUIRED);
            workflow.setRetryable(false);
            workflow.setLastErrorCode("APPLICATION_STATE_MISMATCH");
            workflowRepository.saveAndFlush(workflow);
            return response(workflow, application);
        }

        applyReference(application, workflow.getDocumentType(), reference);
        application.setActiveDocumentWorkflowId(null);
        ApplicationRecord updated =
                applicationRepository.saveAndFlush(application);
        eventRecorder.recordDocumentReferenceChanged(
                updated,
                Instant.now(),
                new ApplicationCommandActor(
                        workflow.getActorType(),
                        workflow.getActorId(),
                        workflow.getSource()),
                workflow.getDocumentType()
                        + " current approved reference replaced by workflow.");
        reconciliationService.markHealthy(updated);
        workflow.setStatus(ApplicationDocumentWorkflowStatus.COMPLETED);
        workflow.setRetryable(false);
        workflow.setLastErrorCode(null);
        workflow.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
        workflowRepository.saveAndFlush(workflow);
        log.info(
                "Document replacement workflow completed operationId={} documentType={} attempts={}",
                workflow.getId(),
                workflow.getDocumentType(),
                workflow.getAttemptCount());
        return response(workflow, updated);
    }

    private void applyReference(
            ApplicationRecord application,
            String documentType,
            DocumentVersionReference reference) {
        if ("CV".equals(documentType)) {
            application.setCvDocumentId(reference.getDocumentId().toString());
            application.setCvDocumentFamilyId(
                    reference.getDocumentFamilyId().toString());
            application.setCvDocumentVersion(reference.getVersion());
            application.setCvDocumentContentSha256(
                    reference.getContentSha256());
            application.setCvDocumentSourceType(reference.getSourceType());
            application.setCvDocumentOriginalContentSha256(
                    reference.getOriginalContentSha256());
            application.setCvDocumentSelectedAt(
                    LocalDateTime.now(ZoneOffset.UTC));
            application.setCvDocumentEvidenceProvenance(
                    reference.getEvidenceProvenance());
            application.setCvDocumentGroundingState(
                    reference.getGroundingState());
        } else {
            application.setCoverLetterDocumentId(
                    reference.getDocumentId().toString());
            application.setCoverLetterDocumentFamilyId(
                    reference.getDocumentFamilyId().toString());
            application.setCoverLetterDocumentVersion(reference.getVersion());
            application.setCoverLetterDocumentContentSha256(
                    reference.getContentSha256());
            application.setCoverLetterDocumentSourceType(
                    reference.getSourceType());
            application.setCoverLetterDocumentOriginalContentSha256(
                    reference.getOriginalContentSha256());
            application.setCoverLetterDocumentSelectedAt(
                    LocalDateTime.now(ZoneOffset.UTC));
            application.setCoverLetterDocumentEvidenceProvenance(
                    reference.getEvidenceProvenance());
            application.setCoverLetterDocumentGroundingState(
                    reference.getGroundingState());
        }
    }

    private void requireReplaceable(ApplicationRecord application) {
        if ((application.getStatus() != ApplicationStatus.SAVED
                        && application.getStatus()
                                != ApplicationStatus.DOCUMENTS_GENERATED)
                || application.getApplicationUsedCvDocumentId() != null) {
            throw new InvalidStatusException(
                    "Documents cannot be replaced after the application has been marked as applied.");
        }
    }

    private void requireMatchingBegin(
            ApplicationDocumentWorkflow workflow,
            String ownerId,
            UUID applicationId,
            String documentType,
            String requestSha256) {
        if (workflow.getWorkflowType() != TYPE
                || !workflow.getUserId().equals(ownerId)
                || !workflow.getApplicationId().equals(applicationId)
                || !Objects.equals(workflow.getDocumentType(), documentType)
                || !Objects.equals(
                        workflow.getRequestSha256(), requestSha256)) {
            throw new InvalidStatusException(
                    "Application already has a different document workflow in progress");
        }
    }

    private ApplicationDocumentWorkflow ownedWorkflow(
            String ownerId,
            UUID applicationId,
            UUID operationId) {
        ApplicationDocumentWorkflow workflow = workflowRepository
                .findByIdAndUserId(operationId, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        requireReplacementWorkflow(workflow, applicationId);
        return workflow;
    }

    private ApplicationDocumentWorkflow ownedWorkflowForUpdate(
            String ownerId,
            UUID applicationId,
            UUID operationId) {
        ApplicationDocumentWorkflow workflow = workflowRepository
                .findForUpdateById(operationId)
                .filter(item -> item.getUserId().equals(ownerId))
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
        requireReplacementWorkflow(workflow, applicationId);
        return workflow;
    }

    private void requireReplacementWorkflow(
            ApplicationDocumentWorkflow workflow,
            UUID applicationId) {
        if (workflow.getWorkflowType() != TYPE
                || !workflow.getApplicationId().equals(applicationId)) {
            throw ResourceNotFoundException.applicationNotFound();
        }
    }

    private ApplicationRecord ownedApplication(
            String ownerId, UUID applicationId) {
        return applicationRepository
                .findByIdAndUserId(applicationId, ownerId)
                .orElseThrow(ResourceNotFoundException::applicationNotFound);
    }

    private String currentDocumentId(
            ApplicationRecord application, String documentType) {
        return "CV".equals(documentType)
                ? application.getCvDocumentId()
                : application.getCoverLetterDocumentId();
    }

    private void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new InvalidStatusException(
                    "Current application document reference is missing or invalid");
        }
    }

    private String normalizeRecoveryCode(String value) {
        if (value == null
                || !value.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            return "REPLACEMENT_STEP_FAILED";
        }
        return value;
    }

    private DocumentReplacementWorkflowResponse response(
            ApplicationDocumentWorkflow workflow,
            ApplicationRecord application) {
        boolean completed = workflow.getStatus()
                == ApplicationDocumentWorkflowStatus.COMPLETED;
        return DocumentReplacementWorkflowResponse.builder()
                .operationId(workflow.getId())
                .applicationId(workflow.getApplicationId())
                .documentType(workflow.getDocumentType())
                .sourceDocumentId(uuid(workflow.getSourceDocumentId()))
                .replacementDocumentId(
                        uuid(workflow.getReplacementDocumentId()))
                .operationStatus(workflow.getStatus().name())
                .retryable(workflow.isRetryable())
                .recoveryCode(workflow.getLastErrorCode())
                .completedAt(workflow.getCompletedAt())
                .cvDocumentId(application.getCvDocumentId())
                .coverLetterDocumentId(
                        application.getCoverLetterDocumentId())
                .message(completed
                        ? "Application document replacement completed."
                        : "Application document replacement is pending recovery.")
                .build();
    }

    private UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }
}
