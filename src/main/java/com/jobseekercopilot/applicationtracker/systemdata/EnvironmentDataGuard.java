package com.jobseekercopilot.applicationtracker.systemdata;

import com.jobseekercopilot.applicationtracker.config.EnvironmentDataProperties;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EnvironmentDataGuard {
    private static final Set<String> SAFE_ENVIRONMENTS = Set.of("e2e");

    private final EnvironmentDataProperties properties;
    private final Environment environment;

    public EnvironmentDataGuard(EnvironmentDataProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public void requireEnabled() {
        Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(EnvironmentDataGuard::normalise)
                .filter(profile -> !profile.isBlank())
                .collect(Collectors.toSet());
        List<String> configuredAllowed = properties.getAllowedEnvironments();
        Set<String> allowed = (configuredAllowed == null ? List.<String>of() : configuredAllowed).stream()
                .map(EnvironmentDataGuard::normalise)
                .filter(profile -> !profile.isBlank())
                .collect(Collectors.toSet());

        if (!properties.isEnabled()
                || activeProfiles.size() != 1
                || allowed.size() != 1
                || !SAFE_ENVIRONMENTS.containsAll(activeProfiles)
                || !SAFE_ENVIRONMENTS.containsAll(allowed)
                || !activeProfiles.equals(allowed)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Environment data management is disabled for this runtime profile");
        }
    }

    public String activeEnvironment() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 1 ? normalise(profiles[0]) : "disabled";
    }

    private static String normalise(String profile) {
        return profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
    }
}
