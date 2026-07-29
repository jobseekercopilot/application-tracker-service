package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Exact immutable evidence revision selected for a document")
public record EvidenceRevisionReference(
        UUID entryId,
        UUID revisionId,
        int revisionNumber,
        EvidenceSection category,
        String contentDigest) {
}
