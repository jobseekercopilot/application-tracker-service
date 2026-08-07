package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Canonical immutable Document Store version descriptor")
public class DocumentVersionReference {
    private UUID documentId;
    private UUID documentFamilyId;
    private String jobId;
    private DocumentType documentType;
    private Integer version;
    private String contentSha256;
    private DocumentEvidenceProvenance evidenceProvenance;
    private DocumentGroundingState groundingState;
    @Builder.Default
    private DocumentAvailabilityState availability =
            DocumentAvailabilityState.AVAILABLE;
    private String unavailableReason;
    private LocalDateTime unavailableAt;
    private UUID parentDocumentId;
    private Integer parentDocumentVersion;
}
