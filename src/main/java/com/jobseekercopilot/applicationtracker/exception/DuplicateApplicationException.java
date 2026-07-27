package com.jobseekercopilot.applicationtracker.exception;

public class DuplicateApplicationException extends ApplicationCreationConflictException {

    public DuplicateApplicationException() {
        super("An application for this canonical job is already tracked for the owner.");
    }
}
