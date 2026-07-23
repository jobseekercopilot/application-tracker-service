package com.jobseekercopilot.applicationtracker.dto;

import jakarta.validation.constraints.NotBlank;
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

    @NotBlank(message = "documentId is required")
    private String documentId;
}
