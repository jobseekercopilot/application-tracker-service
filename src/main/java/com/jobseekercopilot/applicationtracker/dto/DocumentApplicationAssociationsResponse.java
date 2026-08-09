package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Content-free authoritative application associations for one exact document version")
public record DocumentApplicationAssociationsResponse(
        UUID documentId,
        int associationCount,
        List<DocumentApplicationAssociation> associations) {
}
