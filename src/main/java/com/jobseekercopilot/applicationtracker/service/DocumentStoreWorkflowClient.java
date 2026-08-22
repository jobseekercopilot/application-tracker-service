package com.jobseekercopilot.applicationtracker.service;

import java.util.List;
import java.util.UUID;

public interface DocumentStoreWorkflowClient {

    void softDeleteGeneratedDocuments(
            String ownerId,
            UUID operationId,
            UUID applicationId,
            List<UUID> documentIds);
}
