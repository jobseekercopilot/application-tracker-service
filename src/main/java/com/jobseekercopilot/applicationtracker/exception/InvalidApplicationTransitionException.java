package com.jobseekercopilot.applicationtracker.exception;

import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;

public class InvalidApplicationTransitionException extends RuntimeException {

    public InvalidApplicationTransitionException(
            ApplicationStatus current, ApplicationStatus target) {
        super("Application status transition from "
                + current
                + " to "
                + target
                + " is not allowed.");
    }
}
