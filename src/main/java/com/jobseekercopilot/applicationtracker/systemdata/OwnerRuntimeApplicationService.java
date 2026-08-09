package com.jobseekercopilot.applicationtracker.systemdata;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerRuntimeApplicationService {
    private final OwnerRuntimeApplicationRepository repository;

    public OwnerRuntimeApplicationService(
            OwnerRuntimeApplicationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OwnerRuntimeApplicationSummary reset(UUID ownerId) {
        return repository.delete(ownerId.toString());
    }

    @Transactional(readOnly = true)
    public OwnerRuntimeApplicationSummary verify(UUID ownerId) {
        return repository.summary(ownerId.toString());
    }
}
