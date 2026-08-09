package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
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
@Schema(
        description = "Explicit desired state for one optional application document slot. Omission of this object is never interpreted as clearing the slot.")
public class DocumentSelectionCommand {

    @NotNull(message = "state is required")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private DocumentSelectionState state;

    @Schema(
            description = "Exact approved Document Store version. Required only when state is SELECTED.",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID documentId;

    @AssertTrue(
            message = "documentId is required for SELECTED and must be absent for OMITTED")
    @Schema(hidden = true)
    public boolean isStateAndDocumentIdConsistent() {
        return state == null
                || (state == DocumentSelectionState.SELECTED
                        ? documentId != null
                        : documentId == null);
    }
}
