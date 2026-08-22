package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Starts or resumes a durable application document replacement")
public class BeginDocumentReplacementRequest {

    @NotBlank(message = "documentType is required")
    @Pattern(
            regexp = "CV|COVER_LETTER",
            message = "documentType must be CV or COVER_LETTER")
    private String documentType;

    @NotBlank(message = "requestSha256 is required")
    @Pattern(
            regexp = "^[0-9a-f]{64}$",
            message = "requestSha256 must be a lowercase SHA-256 value")
    private String requestSha256;
}
