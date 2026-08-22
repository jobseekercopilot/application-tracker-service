package com.jobseekercopilot.applicationtracker.systemdata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(
        description = "Versioned, owner- and scenario-scoped Application Tracker fixture request",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SystemDataApplicationSeedRequest(
        @NotNull
        @Pattern(regexp = "2\\.0\\.0", message = "must be the supported version 2.0.0")
        @Schema(example = "2.0.0", requiredMode = Schema.RequiredMode.REQUIRED)
        String schemaVersion,

        @NotNull
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,54}-v[1-9][0-9]{0,6}")
        @Size(max = 64)
        @Schema(example = "demo-ready-v1", requiredMode = Schema.RequiredMode.REQUIRED)
        String scenarioId,

        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID userId,

        @NotNull
        @Size(max = 100)
        @Valid
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<@NotNull @Valid SystemDataApplicationSeedRecord> applications) {

    public SystemDataApplicationSeedRequest {
        applications = applications == null ? null : List.copyOf(applications);
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("Unsupported application fixture envelope field.");
    }
}
