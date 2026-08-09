package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(
        description = """
                Immutable profile, evidence-snapshot and claim provenance
                retained with an exact document version.
                """)
public record DocumentEvidenceProvenance(
        UUID profileRevisionId,
        String profileContentDigest,
        UUID evidenceSnapshotId,
        String evidenceSnapshotDigest,
        List<EvidenceRevisionReference> evidenceRevisions,
        List<EvidenceSection> sectionOrder,
        ValidatedClaimLedger claimLedger,
        OffsetDateTime generatedAt) {

    public DocumentEvidenceProvenance {
        evidenceRevisions = evidenceRevisions == null
                ? null
                : List.copyOf(evidenceRevisions);
        sectionOrder =
                sectionOrder == null ? null : List.copyOf(sectionOrder);
    }
}
