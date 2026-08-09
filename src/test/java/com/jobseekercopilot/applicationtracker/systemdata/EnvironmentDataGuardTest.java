package com.jobseekercopilot.applicationtracker.systemdata;

import com.jobseekercopilot.applicationtracker.config.EnvironmentDataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentDataGuardTest {

    @Test
    void permitsOnlyEnabledExplicitE2eProfile() {
        EnvironmentDataGuard guard = guard(true, List.of("e2e"), "e2e");

        assertDoesNotThrow(guard::requireEnabled);
        assertEquals("e2e", guard.activeEnvironment());
    }

    @Test
    void runtimeOwnerCleanupAlsoRequiresExplicitDatabaseIsolation() {
        EnvironmentDataGuard guard = guard(
                true, false, List.of("e2e"), "e2e");
        assertForbidden(guard, true);

        EnvironmentDataGuard isolated = guard(
                true, true, List.of("e2e"), "e2e");
        assertDoesNotThrow(isolated::requireRuntimeOwnerCleanup);
    }

    @Test
    void deniesDisabledDefaultUnknownProductionAndMixedProfiles() {
        assertForbidden(guard(false, List.of("e2e"), "e2e"));
        assertForbidden(guard(true, List.of("e2e")));
        assertForbidden(guard(true, List.of("e2e"), "test"));
        assertForbidden(guard(true, List.of("prod"), "prod"));
        assertForbidden(guard(true, List.of("production"), "production"));
        assertForbidden(guard(true, List.of("e2e"), "e2e", "test"));
    }

    @Test
    void deniesUnsafeOrAmbiguousConfiguredAllowLists() {
        assertForbidden(guard(true, List.of("default")));
        assertForbidden(guard(true, List.of("demo"), "demo"));
        assertForbidden(guard(true, List.of("e2e", "demo"), "e2e"));
        assertForbidden(guard(true, List.of(), "e2e"));
    }

    private void assertForbidden(EnvironmentDataGuard guard) {
        assertForbidden(guard, false);
    }

    private void assertForbidden(
            EnvironmentDataGuard guard, boolean runtimeOwnerCleanup) {
        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        runtimeOwnerCleanup
                                ? guard::requireRuntimeOwnerCleanup
                                : guard::requireEnabled);
        assertEquals(403, exception.getStatusCode().value());
    }

    private EnvironmentDataGuard guard(
            boolean enabled,
            List<String> allowedEnvironments,
            String... activeProfiles) {
        return guard(enabled, false, allowedEnvironments, activeProfiles);
    }

    private EnvironmentDataGuard guard(
            boolean enabled,
            boolean isolatedDatabase,
            List<String> allowedEnvironments,
            String... activeProfiles) {
        EnvironmentDataProperties properties = new EnvironmentDataProperties();
        properties.setEnabled(enabled);
        properties.setIsolatedDatabase(isolatedDatabase);
        properties.setAllowedEnvironments(allowedEnvironments);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new EnvironmentDataGuard(properties, environment);
    }
}
