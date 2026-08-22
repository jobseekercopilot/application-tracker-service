package com.jobseekercopilot.applicationtracker.exception;

public class DocumentReferenceUnavailableException extends RuntimeException {
    public DocumentReferenceUnavailableException() {
        super("Document reference validation is temporarily unavailable.");
    }
}
