package com.jobseekercopilot.applicationtracker.exception;

public class InvalidDocumentReferenceException extends RuntimeException {
    public InvalidDocumentReferenceException() {
        super("Document reference is not eligible for this application.");
    }
}
