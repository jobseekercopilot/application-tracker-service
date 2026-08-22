package com.jobseekercopilot.applicationtracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDocumentReferenceRequest {
    @NotBlank(message = "documentType is required")
    private String documentType;

    @NotNull(message = "documentId is required")
    private UUID documentId;
}
