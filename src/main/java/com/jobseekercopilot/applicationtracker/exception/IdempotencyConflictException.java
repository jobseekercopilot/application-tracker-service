package com.jobseekercopilot.applicationtracker.exception;

public class IdempotencyConflictException extends ApplicationCreationConflictException {

    public IdempotencyConflictException() {
        super("Idempotency key was already used for a different application command.");
    }
}
