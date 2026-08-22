package com.jobseekercopilot.applicationtracker.systemdata;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class SyntheticOwnerId {
    private static final String NAMESPACE = "job-seeker-copilot:system-data:";

    private SyntheticOwnerId() {
    }

    static void requireMatches(
            String scenarioId, String identityKey, UUID suppliedOwnerId) {
        // REGISTRATION_CLEAN begins without an account, so the public
        // registration journey necessarily receives an authentication-owned
        // runtime UUID. The endpoint is already restricted to the isolated E2E
        // database and its internal system-data credential; keep this exception
        // pinned to that single catalog identity.
        if ("registration-clean-v1".equals(scenarioId)
                && "registration-primary".equals(identityKey)) {
            return;
        }
        UUID expected = UUID.nameUUIDFromBytes((NAMESPACE
                + scenarioId
                + ":"
                + identityKey
                + ":user").getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(suppliedOwnerId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Owner does not match the named-state synthetic identity");
        }
    }
}
