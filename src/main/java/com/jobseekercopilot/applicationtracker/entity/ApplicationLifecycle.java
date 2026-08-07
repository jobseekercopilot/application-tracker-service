package com.jobseekercopilot.applicationtracker.entity;

import com.jobseekercopilot.applicationtracker.exception.InvalidApplicationTransitionException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ApplicationLifecycle {

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS =
            transitions();

    private ApplicationLifecycle() {
    }

    public static void requireTransition(
            ApplicationStatus current, ApplicationStatus target) {
        if (!allowedTargets(current).contains(target)) {
            throw new InvalidApplicationTransitionException(current, target);
        }
    }

    public static Set<ApplicationStatus> allowedTargets(ApplicationStatus current) {
        return TRANSITIONS.getOrDefault(current, Set.of());
    }

    public static boolean isTerminal(ApplicationStatus status) {
        return allowedTargets(status).isEmpty();
    }

    private static Map<ApplicationStatus, Set<ApplicationStatus>> transitions() {
        EnumMap<ApplicationStatus, Set<ApplicationStatus>> transitions =
                new EnumMap<>(ApplicationStatus.class);
        transitions.put(
                ApplicationStatus.SAVED,
                immutable(
                        ApplicationStatus.DOCUMENTS_GENERATED,
                        ApplicationStatus.APPLIED));
        transitions.put(
                ApplicationStatus.DOCUMENTS_GENERATED,
                immutable(ApplicationStatus.APPLIED));
        transitions.put(
                ApplicationStatus.APPLIED,
                immutable(
                        ApplicationStatus.INTERVIEW,
                        ApplicationStatus.OFFER,
                        ApplicationStatus.UNSUCCESSFUL,
                        ApplicationStatus.WITHDRAWN));
        transitions.put(
                ApplicationStatus.INTERVIEW,
                immutable(
                        ApplicationStatus.OFFER,
                        ApplicationStatus.UNSUCCESSFUL,
                        ApplicationStatus.WITHDRAWN));
        transitions.put(
                ApplicationStatus.OFFER,
                immutable(
                        ApplicationStatus.ACCEPTED,
                        ApplicationStatus.REJECTED_BY_USER,
                        ApplicationStatus.UNSUCCESSFUL,
                        ApplicationStatus.WITHDRAWN));
        transitions.put(ApplicationStatus.UNSUCCESSFUL, Set.of());
        transitions.put(ApplicationStatus.ACCEPTED, Set.of());
        transitions.put(ApplicationStatus.REJECTED_BY_USER, Set.of());
        transitions.put(ApplicationStatus.WITHDRAWN, Set.of());
        return Map.copyOf(transitions);
    }

    private static Set<ApplicationStatus> immutable(
            ApplicationStatus first, ApplicationStatus... rest) {
        EnumSet<ApplicationStatus> values = EnumSet.of(first, rest);
        return Set.copyOf(values);
    }
}
