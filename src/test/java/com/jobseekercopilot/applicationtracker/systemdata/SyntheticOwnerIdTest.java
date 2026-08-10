package com.jobseekercopilot.applicationtracker.systemdata;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SyntheticOwnerIdTest {
    @Test
    void permitsTheIsolatedRegistrationJourneyRuntimeOwner() {
        assertThatCode(() -> SyntheticOwnerId.requireMatches(
                "registration-clean-v1",
                "registration-primary",
                UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRuntimeOwnersForEveryOtherNamedStateIdentity() {
        assertThatThrownBy(() -> SyntheticOwnerId.requireMatches(
                "demo-ready-v1",
                "alex-taylor",
                UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
