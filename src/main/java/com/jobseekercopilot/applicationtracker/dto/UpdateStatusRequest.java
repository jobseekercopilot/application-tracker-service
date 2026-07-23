package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to update the status of an application")
public class UpdateStatusRequest {

    @NotBlank(message = "status is required")
    @Schema(
            description = "New status value",
            example = "APPLIED",
            allowableValues = {
                    "DOCUMENTS_GENERATED",
                    "APPLIED",
                    "INTERVIEW",
                    "UNSUCCESSFUL",
                    "OFFER",
                    "ACCEPTED",
                    "REJECTED_BY_USER",
                    "WITHDRAWN"
            },
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String status;
}
