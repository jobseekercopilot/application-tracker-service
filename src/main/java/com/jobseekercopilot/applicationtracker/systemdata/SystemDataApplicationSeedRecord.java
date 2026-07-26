package com.jobseekercopilot.applicationtracker.systemdata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(
        description = "Constrained application fixture record; ownership and scenario come from the request envelope",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SystemDataApplicationSeedRecord(
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,

        @NotBlank
        @Size(min = 1, max = 200)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String jobId,

        @Size(max = 200)
        String canonicalJobId,

        @Size(max = 64)
        String provider,

        @Size(max = 200)
        String externalJobId,

        @NotBlank
        @Size(min = 1, max = 300)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String jobTitle,

        @NotBlank
        @Size(min = 1, max = 300)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String companyName,

        @Size(max = 300)
        String location,

        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID cvDocumentId,

        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID coverLetterDocumentId,

        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ApplicationStatus status,

        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime createdAt,

        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime updatedAt,

        LocalDateTime appliedAt) {

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("Unsupported application fixture field.");
    }
}
