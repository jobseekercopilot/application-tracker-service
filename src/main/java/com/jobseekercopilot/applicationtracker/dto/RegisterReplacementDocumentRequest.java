package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Registers the exact Store document created for a durable replacement")
public class RegisterReplacementDocumentRequest {

    @NotNull(message = "replacementDocumentId is required")
    private UUID replacementDocumentId;
}
