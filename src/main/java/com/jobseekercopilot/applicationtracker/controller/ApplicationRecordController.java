package com.jobseekercopilot.applicationtracker.controller;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.exception.ErrorResponse;
import com.jobseekercopilot.applicationtracker.exception.SecurityErrorResponse;
import com.jobseekercopilot.applicationtracker.security.ApplicationOwnerResolver;
import com.jobseekercopilot.applicationtracker.service.ApplicationRecordService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
@Tag(name = "Application Records", description = "Endpoints for tracking job applications")
public class ApplicationRecordController {

    private final ApplicationRecordService service;
    private final ApplicationOwnerResolver ownerResolver;

    @PostMapping
    @Operation(summary = "Create an application record", description = "Creates a new application record with references to generated documents")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Application record created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid fields")
    })
    public ResponseEntity<ApplicationRecordResponse> createApplication(
            @Valid @RequestBody CreateApplicationRequest request,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, request.getUserId());
        ApplicationRecordResponse response = service.createApplication(ownerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
        ApplicationRecordResponse response = service.updateStatus(ownerId, id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/document-reference")
    @Operation(summary = "Update the active document reference for an application")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "serviceToken")
    public ResponseEntity<ApplicationRecordResponse> updateDocumentReference(
            @Parameter(description = "UUID of the application record") @PathVariable UUID id,
            @Valid @RequestBody UpdateDocumentReferenceRequest request,
            @Parameter(description = "Required owner context for approved service identities")
            @RequestHeader(value = ApplicationOwnerResolver.OWNER_HEADER, required = false)
            String requestedOwner,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = ownerResolver.resolve(authentication, requestedOwner);
        return ResponseEntity.ok(service.updateDocumentReference(ownerId, id, request));
    }

    @PostMapping("/{id}/withdraw-generated")
    @Operation(summary = "Withdraw generated application", description = "Removes a generated-only application record and resets the job to NEW")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Generated application withdrawn"),
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
        return ResponseEntity.ok(
                service.withdrawGeneratedApplication(ownerId, id, authorization));
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
        service.deleteApplication(ownerId, id);
        return ResponseEntity.noContent().build();
    }
}
