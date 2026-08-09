package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Complete desired CV and cover-letter selection. Both slot objects are required so older or partial clients cannot accidentally clear a selection.")
public class SaveDocumentSelectionsRequest {

    @Valid
    @NotNull(message = "cvSelection is required")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private DocumentSelectionCommand cvSelection;

    @Valid
    @NotNull(message = "coverLetterSelection is required")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private DocumentSelectionCommand coverLetterSelection;

    @NotNull(message = "expectedVersion is required")
    @PositiveOrZero(message = "expectedVersion must be zero or greater")
    @Schema(
            description = "Application record version observed by the client.",
            example = "3",
            minimum = "0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long expectedVersion;
}
