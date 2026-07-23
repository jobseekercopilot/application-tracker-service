package com.jobseekercopilot.applicationtracker.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException applicationNotFound() {
        return new ResourceNotFoundException("Application record not found.");
    }
}
