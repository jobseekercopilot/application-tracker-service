package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import java.util.UUID;

public interface DocumentReferenceVerifier {
    DocumentVersionReference verify(
            String ownerId,
            UUID documentId,
            String expectedJobId,
            DocumentType expectedType);
}
