package com.jobseekercopilot.applicationtracker.controller;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.ApplicationHistoryResponse;
import com.jobseekercopilot.applicationtracker.dto.BeginDocumentReplacementRequest;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.DocumentReplacementWorkflowResponse;
import com.jobseekercopilot.applicationtracker.dto.DocumentReferenceReconciliationResponse;
import com.jobseekercopilot.applicationtracker.dto.RegisterReplacementDocumentRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.exception.ErrorResponse;
import com.jobseekercopilot.applicationtracker.exception.SecurityErrorResponse;
import com.jobseekercopilot.applicationtracker.security.ApplicationActorResolver;
import com.jobseekercopilot.applicationtracker.security.ApplicationOwnerResolver;
import com.jobseekercopilot.applicationtracker.service.ApplicationCreationResult;
import com.jobseekercopilot.applicationtracker.service.ApplicationHistoryService;
import com.jobseekercopilot.applicationtracker.service.ApplicationDocumentReconciliationService;
import com.jobseekercopilot.applicationtracker.service.ApplicationRecordService;
import com.jobseekercopilot.applicationtracker.service.ApplicationReplacementWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
@Validated
@Tag(name = "Application Records", description = "Endpoints for tracking job applications")
public class ApplicationRecordController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ApplicationRecordService service;
    private final ApplicationReplacementWorkflowService replacementWorkflowService;
    private final ApplicationDocumentReconciliationService
            reconciliationService;
    private final ApplicationHistoryService historyService;
    private final ApplicationOwnerResolver ownerResolver;
    private final ApplicationActorResolver actorResolver;

    @PostMapping
    @Operation(
            summary = "Create or replay an application record",
            description = """
                    Creates an authoritative generated, manual or external application.
                    Repeating the same owner-scoped Idempotency-Key and payload returns
                    the existing record.
                    """)
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Application record created successfully"),
            @ApiResponse(
                    responseCode = "200",
                    description = "Existing application replayed for the same idempotency key"),
            @ApiResponse(responseCode = "400", description = "Validation error or ineligible document reference"),
            @ApiResponse(
                    responseCode = "409",
                    description = "Idempotency key reuse or duplicate canonical application"),
            @ApiResponse(responseCode = "503", description = "Document reference validation unavailable")
    })
    public ResponseEntity<ApplicationRecordResponse> createApplication(
            @Valid @RequestBody CreateApplicationRequest request,
            @Parameter(
                    description = "Owner-scoped retry key. Legacy producers without a key receive deterministic request-based idempotency.",
                    example = "apply-job-456-attempt-1")
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
            String idempotencyKey,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, request.getUserId());
        ApplicationCreationResult result =
                service.createApplication(
                        ownerId,
                        idempotencyKey,
                        request,
                        actorResolver.resolve(authentication));
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.application());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get application by ID")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application record found"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<ApplicationRecordResponse> getApplicationById(
            @Parameter(description = "UUID of the application record") @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = ApplicationOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner);
        ApplicationRecordResponse response = service.getApplicationById(ownerId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/document-reference-reconciliation")
    @Operation(
            summary = "Get durable document-reference reconciliation state",
            description = "Reports whether current and frozen application references are healthy, safely repaired, invalid or temporarily unverifiable")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Owner-scoped reconciliation state returned",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(
                                    implementation =
                                            DocumentReferenceReconciliationResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Application record not found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DocumentReferenceReconciliationResponse>
            getDocumentReferenceReconciliation(
                    @Parameter(description = "UUID of the application record")
                    @PathVariable UUID id,
                    @Parameter(description = "Required owner context for approved service identities")
                    @RequestHeader(
                            value = ApplicationOwnerResolver.OWNER_HEADER,
                            required = false)
                    String requestedOwner,
                    @Parameter(hidden = true)
                    Authentication authentication) {
        String ownerId =
                ownerResolver.resolve(authentication, requestedOwner);
        return ResponseEntity.ok(
                reconciliationService.status(ownerId, id));
    }

    @GetMapping(
            value = "/{id}/history",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get immutable application activity history",
            description = "Returns an owner-scoped chronological page and whether its latest event reconciles with current application state.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Ordered activity page returned",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(
                                    implementation = ApplicationHistoryResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Application record not found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApplicationHistoryResponse> getApplicationHistory(
            @Parameter(description = "UUID of the application record")
            @PathVariable UUID id,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = ApplicationOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner);
        return ResponseEntity.ok(
                historyService.getHistory(ownerId, id, page, size));
    }

    @GetMapping(value = "/user/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get applications by user ID")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Owner-scoped application records",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(
                                    schema = @Schema(implementation = ApplicationRecordResponse.class)))),
            @ApiResponse(
                    responseCode = "401",
                    description = "Missing, invalid or ambiguous authentication",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SecurityErrorResponse.class))),
            @ApiResponse(
                    responseCode = "403",
                    description = "Authenticated identity lacks reader permission",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SecurityErrorResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Bearer subject and requested owner do not match",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<ApplicationRecordResponse>> getApplicationsForUser(
            @Parameter(description = "User ID to retrieve applications for") @PathVariable String userId,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, userId);
        List<ApplicationRecordResponse> responses = service.getApplicationsForUser(ownerId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/document/{documentId}")
    @Operation(summary = "Get application by generated document ID")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application record found"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<ApplicationRecordResponse> getApplicationByDocumentId(
            @Parameter(description = "Generated CV or cover letter document ID") @PathVariable String documentId,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = ApplicationOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner);
        ApplicationRecordResponse response =
                service.getApplicationByDocumentId(ownerId, documentId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping(
            value = "/{id}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update application status")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Status updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApplicationRecordResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid status value",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "Invalid lifecycle transition or stale/concurrent record version",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Application record not found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApplicationRecordResponse> updateStatus(
            @Parameter(description = "UUID of the application record") @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest request,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, null);
        ApplicationRecordResponse response = service.updateStatus(
                ownerId,
                id,
                request,
                actorResolver.resolve(authentication));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/document-reference")
    @Operation(summary = "Replace a current approved document reference before application use")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Current reference replaced"),
            @ApiResponse(responseCode = "400", description = "Reference is invalid or already frozen"),
            @ApiResponse(responseCode = "404", description = "Application record not found"),
            @ApiResponse(responseCode = "503", description = "Document reference validation unavailable")
    })
    public ResponseEntity<ApplicationRecordResponse> updateDocumentReference(
            @Parameter(description = "UUID of the application record") @PathVariable UUID id,
            @Valid @RequestBody UpdateDocumentReferenceRequest request,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = ApplicationOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner);
        return ResponseEntity.ok(service.updateDocumentReference(
                ownerId,
                id,
                request,
                actorResolver.resolve(authentication)));
    }

    @PostMapping("/{id}/document-replacements")
    @Operation(
            summary = "Start or resume a durable document replacement",
            description = "Reserves one Tracker-owned operation before any replacement document or file is written")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Existing replacement operation replayed"),
            @ApiResponse(responseCode = "202", description = "Replacement operation accepted"),
            @ApiResponse(responseCode = "400", description = "Application is locked or request differs from active operation"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<DocumentReplacementWorkflowResponse>
            beginDocumentReplacement(
                    @PathVariable UUID id,
                    @Valid @RequestBody BeginDocumentReplacementRequest request,
                    @RequestHeader(
                            value = ApplicationOwnerResolver.OWNER_HEADER,
                            required = false)
                    String requestedOwner,
                    @Parameter(hidden = true)
                    Authentication authentication) {
        String ownerId = ownerResolver.resolve(
                authentication, requestedOwner);
        DocumentReplacementWorkflowResponse response =
                replacementWorkflowService.begin(
                        ownerId,
                        id,
                        request,
                        actorResolver.resolve(authentication));
        return "COMPLETED".equals(response.getOperationStatus())
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    @PatchMapping(
            "/{id}/document-replacements/{operationId}/replacement-document")
    @Operation(summary = "Register the Store document created for a replacement")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DocumentReplacementWorkflowResponse>
            registerReplacementDocument(
                    @PathVariable UUID id,
                    @PathVariable UUID operationId,
                    @Valid @RequestBody RegisterReplacementDocumentRequest request,
                    @RequestHeader(
                            value = ApplicationOwnerResolver.OWNER_HEADER,
                            required = false)
                    String requestedOwner,
                    @Parameter(hidden = true)
                    Authentication authentication) {
        String ownerId = ownerResolver.resolve(
                authentication, requestedOwner);
        return ResponseEntity.accepted().body(
                replacementWorkflowService.registerReplacement(
                        ownerId,
                        id,
                        operationId,
                        request.getReplacementDocumentId()));
    }

    @PatchMapping(
            "/{id}/document-replacements/{operationId}/complete")
    @Operation(summary = "Verify and commit a durable document replacement")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Replacement committed"),
            @ApiResponse(responseCode = "400", description = "Workflow or application state mismatch"),
            @ApiResponse(responseCode = "404", description = "Replacement operation not found"),
            @ApiResponse(responseCode = "503", description = "Document reference validation unavailable")
    })
    public ResponseEntity<DocumentReplacementWorkflowResponse>
            completeDocumentReplacement(
                    @PathVariable UUID id,
                    @PathVariable UUID operationId,
                    @RequestHeader(
                            value = ApplicationOwnerResolver.OWNER_HEADER,
                            required = false)
                    String requestedOwner,
                    @Parameter(hidden = true)
                    Authentication authentication) {
        String ownerId = ownerResolver.resolve(
                authentication, requestedOwner);
        DocumentReplacementWorkflowResponse response =
                replacementWorkflowService.complete(
                        ownerId, id, operationId);
        return "COMPLETED".equals(response.getOperationStatus())
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    @PatchMapping(
            "/{id}/document-replacements/{operationId}/recovery-required")
    @Operation(summary = "Record a recoverable replacement dependency failure")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DocumentReplacementWorkflowResponse>
            markDocumentReplacementRecoveryRequired(
                    @PathVariable UUID id,
                    @PathVariable UUID operationId,
                    @RequestHeader(
                            value = ApplicationOwnerResolver.OWNER_HEADER,
                            required = false)
                    String requestedOwner,
                    @Parameter(hidden = true)
                    Authentication authentication) {
        String ownerId = ownerResolver.resolve(
                authentication, requestedOwner);
        return ResponseEntity.accepted().body(
                replacementWorkflowService.markRecoveryRequired(
                        ownerId,
                        id,
                        operationId,
                        "REPLACEMENT_STEP_FAILED",
                        true));
    }

    @GetMapping("/{id}/document-replacements/{operationId}")
    @Operation(summary = "Get durable document-replacement status")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<DocumentReplacementWorkflowResponse>
            documentReplacementStatus(
                    @PathVariable UUID id,
                    @PathVariable UUID operationId,
                    @RequestHeader(
                            value = ApplicationOwnerResolver.OWNER_HEADER,
                            required = false)
                    String requestedOwner,
                    @Parameter(hidden = true)
                    Authentication authentication) {
        String ownerId = ownerResolver.resolve(
                authentication, requestedOwner);
        return ResponseEntity.ok(
                replacementWorkflowService.status(
                        ownerId, id, operationId));
    }

    @PostMapping("/{id}/withdraw-generated")
    @Operation(
            summary = "Withdraw generated application",
            description = "Starts or resumes durable generated-document cleanup; the application is removed only after cleanup completes")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Generated application withdrawn"),
            @ApiResponse(responseCode = "202", description = "Withdrawal accepted and awaiting recoverable document cleanup"),
            @ApiResponse(responseCode = "400", description = "Application cannot be withdrawn because it has already progressed"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<WithdrawGeneratedApplicationResponse> withdrawGeneratedApplication(
            @Parameter(description = "UUID of the generated-only application record") @PathVariable UUID id,
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, null);
        WithdrawGeneratedApplicationResponse response =
                service.withdrawGeneratedApplication(
                        ownerId,
                        id,
                        authorization,
                        actorResolver.resolve(authentication));
        return response.isWithdrawn()
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    @GetMapping("/{id}/withdraw-generated")
    @Operation(summary = "Get generated-application withdrawal recovery status")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Durable withdrawal state"),
            @ApiResponse(responseCode = "404", description = "Withdrawal operation not found")
    })
    public ResponseEntity<WithdrawGeneratedApplicationResponse>
            generatedWithdrawalStatus(
                    @PathVariable UUID id,
                    @Parameter(hidden = true)
                    Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, null);
        return ResponseEntity.ok(
                service.generatedWithdrawalStatus(ownerId, id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an application record")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Application record deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<Void> deleteApplication(
            @Parameter(description = "UUID of the application record to delete") @PathVariable UUID id,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, null);
        service.deleteApplication(
                ownerId, id, actorResolver.resolve(authentication));
        return ResponseEntity.noContent().build();
    }
}
