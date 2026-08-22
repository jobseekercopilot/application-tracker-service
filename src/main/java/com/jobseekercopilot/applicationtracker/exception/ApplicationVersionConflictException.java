package com.jobseekercopilot.applicationtracker.exception;

public class ApplicationVersionConflictException extends RuntimeException {

    public ApplicationVersionConflictException() {
        super("Application was changed by another request. Refresh and retry.");
    }
}
