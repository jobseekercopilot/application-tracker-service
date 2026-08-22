package com.jobseekercopilot.applicationtracker.security;

import com.jobseekercopilot.applicationtracker.service.ApplicationCommandActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ApplicationActorResolver {

    public ApplicationCommandActor resolve(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !StringUtils.hasText(authentication.getName())) {
            throw new AccessDeniedException("Authenticated actor is required");
        }
        if (hasAuthority(authentication, ApplicationAuthorities.USER)) {
            return ApplicationCommandActor.user(authentication.getName());
        }
        if (hasAuthority(authentication, ApplicationAuthorities.PRODUCER)
                || hasAuthority(authentication, ApplicationAuthorities.READER)) {
            return ApplicationCommandActor.service(authentication.getName());
        }
        if (hasAuthority(authentication, ApplicationAuthorities.ENVIRONMENT_DATA)) {
            return ApplicationCommandActor.systemData(authentication.getName());
        }
        throw new AccessDeniedException("Application actor authority is required");
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
