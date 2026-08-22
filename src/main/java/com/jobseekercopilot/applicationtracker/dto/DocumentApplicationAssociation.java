package com.jobseekercopilot.applicationtracker.dto;

import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentApplicationAssociation(
        UUID applicationId,
        DocumentType documentType,
        DocumentApplicationAssociationState associationState,
        ApplicationStatus applicationStatus,
        LocalDateTime frozenAt) {
}
