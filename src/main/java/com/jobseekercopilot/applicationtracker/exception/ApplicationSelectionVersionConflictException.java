package com.jobseekercopilot.applicationtracker.exception;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import lombok.Getter;

@Getter
public class ApplicationSelectionVersionConflictException extends RuntimeException {

    private final ApplicationRecordResponse currentApplication;

    public ApplicationSelectionVersionConflictException(
            ApplicationRecordResponse currentApplication) {
        super("Application was changed by another request. Refresh and retry.");
        this.currentApplication = currentApplication;
    }
}
