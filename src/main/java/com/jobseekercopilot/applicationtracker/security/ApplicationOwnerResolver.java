package com.jobseekercopilot.applicationtracker.security;

import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ApplicationOwnerResolver {

    public static final String OWNER_HEADER = "X-Application-Owner";

    public String resolve(Authentication authentication, String requestedOwner) {
        if (hasAuthority(authentication, ApplicationAuthorities.USER)) {
            String subject = authentication.getName();
            if (!StringUtils.hasText(subject)) {
                throw new AccessDeniedException("Authenticated subject is required");
            }
            if (StringUtils.hasText(requestedOwner) && !subject.equals(requestedOwner)) {
                throw ResourceNotFoundException.applicationNotFound();
            }
            return subject;
        }

        if (hasAuthority(authentication, ApplicationAuthorities.PRODUCER)
                || hasAuthority(authentication, ApplicationAuthorities.READER)) {
            if (!StringUtils.hasText(requestedOwner)) {
                throw new InvalidRequestException(
                        "Application owner is required for service requests.");
            }
            return requestedOwner.trim();
        }

        throw new AccessDeniedException("Application authority is required");
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
