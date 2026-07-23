package com.jobseekercopilot.applicationtracker.controller;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.CreateApplicationRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.dto.UpdateDocumentReferenceRequest;
import com.jobseekercopilot.applicationtracker.dto.WithdrawGeneratedApplicationResponse;
import com.jobseekercopilot.applicationtracker.service.ApplicationRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping
    @Operation(summary = "Create an application record", description = "Creates a new application record with references to generated documents")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Application record created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing or invalid fields")
    })
    public ResponseEntity<ApplicationRecordResponse> createApplication(
            @Valid @RequestBody CreateApplicationRequest request) {
        ApplicationRecordResponse response = service.createApplication(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get application by ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application record found"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<ApplicationRecordResponse> getApplicationById(
            @Parameter(description = "UUID of the application record") @PathVariable UUID id) {
        ApplicationRecordResponse response = service.getApplicationById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get applications by user ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of application records for the user")
    })
    public ResponseEntity<List<ApplicationRecordResponse>> getApplicationsForUser(
            @Parameter(description = "User ID to retrieve applications for") @PathVariable String userId) {
        List<ApplicationRecordResponse> responses = service.getApplicationsForUser(userId);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/document/{documentId}")
    @Operation(summary = "Get application by generated document ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Application record found"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<ApplicationRecordResponse> getApplicationByDocumentId(
            @Parameter(description = "Generated CV or cover letter document ID") @PathVariable String documentId) {
        ApplicationRecordResponse response = service.getApplicationByDocumentId(documentId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update application status")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Status updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid status value"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<ApplicationRecordResponse> updateStatus(
            @Parameter(description = "UUID of the application record") @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest request) {
        ApplicationRecordResponse response = service.updateStatus(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/document-reference")
    @Operation(summary = "Update the active document reference for an application")
    public ResponseEntity<ApplicationRecordResponse> updateDocumentReference(
            @Parameter(description = "UUID of the application record") @PathVariable UUID id,
            @Valid @RequestBody UpdateDocumentReferenceRequest request) {
        return ResponseEntity.ok(service.updateDocumentReference(id, request));
    }

    @PostMapping("/{id}/withdraw-generated")
    @Operation(summary = "Withdraw generated application", description = "Removes a generated-only application record and resets the job to NEW")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Generated application withdrawn"),
            @ApiResponse(responseCode = "400", description = "Application cannot be withdrawn because it has already progressed"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<WithdrawGeneratedApplicationResponse> withdrawGeneratedApplication(
            @Parameter(description = "UUID of the generated-only application record") @PathVariable UUID id) {
        return ResponseEntity.ok(service.withdrawGeneratedApplication(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an application record")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Application record deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Application record not found")
    })
    public ResponseEntity<Void> deleteApplication(
            @Parameter(description = "UUID of the application record to delete") @PathVariable UUID id) {
        service.deleteApplication(id);
        return ResponseEntity.noContent().build();
    }
}
