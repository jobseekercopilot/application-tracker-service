package com.jobseekercopilot.applicationtracker.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobseekercopilot.applicationtracker.exception.InvalidApplicationTransitionException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApplicationLifecycleTest {

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> EXPECTED =
            Map.of(
                    ApplicationStatus.DOCUMENTS_GENERATED,
                    Set.of(ApplicationStatus.APPLIED),
                    ApplicationStatus.APPLIED,
                    Set.of(
                            ApplicationStatus.INTERVIEW,
                            ApplicationStatus.OFFER,
                            ApplicationStatus.UNSUCCESSFUL,
                            ApplicationStatus.WITHDRAWN),
                    ApplicationStatus.INTERVIEW,
                    Set.of(
                            ApplicationStatus.OFFER,
                            ApplicationStatus.UNSUCCESSFUL,
                            ApplicationStatus.WITHDRAWN),
                    ApplicationStatus.OFFER,
                    Set.of(
                            ApplicationStatus.ACCEPTED,
                            ApplicationStatus.REJECTED_BY_USER,
                            ApplicationStatus.UNSUCCESSFUL,
                            ApplicationStatus.WITHDRAWN),
                    ApplicationStatus.UNSUCCESSFUL,
                    Set.of(),
                    ApplicationStatus.ACCEPTED,
                    Set.of(),
                    ApplicationStatus.REJECTED_BY_USER,
                    Set.of(),
                    ApplicationStatus.WITHDRAWN,
                    Set.of());

    @Test
    void completeMatrixAcceptsOnlyApprovedForwardTransitions() {
        for (ApplicationStatus current : ApplicationStatus.values()) {
            assertThat(ApplicationLifecycle.allowedTargets(current))
                    .isEqualTo(EXPECTED.get(current));
            for (ApplicationStatus target : ApplicationStatus.values()) {
                if (EXPECTED.get(current).contains(target)) {
                    assertThatCode(
                                    () -> ApplicationLifecycle.requireTransition(
                                            current, target))
                            .doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(
                                    () -> ApplicationLifecycle.requireTransition(
                                            current, target))
                            .isInstanceOf(InvalidApplicationTransitionException.class)
                            .hasMessage(
                                    "Application status transition from "
                                            + current
                                            + " to "
                                            + target
                                            + " is not allowed.");
                }
            }
        }
    }

    @Test
    void terminalStatusesHaveNoPublicReopenPath() {
        assertThat(ApplicationStatus.values())
                .filteredOn(ApplicationLifecycle::isTerminal)
                .containsExactlyInAnyOrder(
                        ApplicationStatus.UNSUCCESSFUL,
                        ApplicationStatus.ACCEPTED,
                        ApplicationStatus.REJECTED_BY_USER,
                        ApplicationStatus.WITHDRAWN);
    }
}
