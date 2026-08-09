package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

class AuthoritativeJobSourcePolicyTest {

    private static final String LISTING =
            "https://www.jobs.nhs.uk/candidate/jobadvert/C123";
    private static final String ATTRIBUTION = "Vacancy source: NHS Jobs";
    private static final String ATTRIBUTION_URL = "https://www.jobs.nhs.uk/";
    private static final String LICENCE =
            "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/";
    private static final String DISCLAIMER =
            "NHS Jobs does not endorse Job Seeker Copilot.";

    @Test
    void liveModePreservesTheValidatedAuthoritativeMetadata() {
        AuthoritativeJobSourcePolicy.SourceMetadata source =
                new AuthoritativeJobSourcePolicy("LIVE").normalize(
                        "NHS_JOBS",
                        "C123",
                        LISTING,
                        LISTING,
                        ATTRIBUTION,
                        ATTRIBUTION_URL,
                        LICENCE,
                        DISCLAIMER);

        assertThat(source.listingUrl()).isEqualTo(LISTING);
        assertThat(source.applyUrl()).isEqualTo(LISTING);
        assertThat(source.attributionLabel()).isEqualTo(ATTRIBUTION);
        assertThat(source.attributionSourceUrl()).isEqualTo(ATTRIBUTION_URL);
        assertThat(source.licenceUrl()).isEqualTo(LICENCE);
        assertThat(source.disclaimer()).isEqualTo(DISCLAIMER);
    }

    @Test
    void liveModeRejectsFixtureUrlsAndMismatchedVacancyIdentity() {
        AuthoritativeJobSourcePolicy policy =
                new AuthoritativeJobSourcePolicy("LIVE");

        assertThatThrownBy(() -> policy.normalize(
                        "NHS_JOBS",
                        "C123",
                        "https://fixtures.jobseekercopilot.test/nhs-jobs/jobadvert/C123",
                        null,
                        ATTRIBUTION,
                        ATTRIBUTION_URL,
                        LICENCE,
                        DISCLAIMER))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("authoritative NHS Jobs HTTPS vacancy URL");

        assertThatThrownBy(() -> policy.normalize(
                        "NHS_JOBS",
                        "C123",
                        "https://www.jobs.nhs.uk/candidate/jobadvert/C999",
                        null,
                        ATTRIBUTION,
                        ATTRIBUTION_URL,
                        LICENCE,
                        DISCLAIMER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void fixtureModeAcceptsOnlyTheDedicatedFixtureOriginAndPath() {
        String fixture =
                "https://fixtures.jobseekercopilot.test/nhs-jobs/jobadvert/C123";
        AuthoritativeJobSourcePolicy policy =
                new AuthoritativeJobSourcePolicy("FIXTURE");

        assertThat(policy.normalize(
                                "NHS_JOBS",
                                "C123",
                                fixture,
                                fixture,
                                ATTRIBUTION,
                                ATTRIBUTION_URL,
                                LICENCE,
                                DISCLAIMER)
                        .listingUrl())
                .isEqualTo(fixture);

        assertThatThrownBy(() -> policy.normalize(
                        "NHS_JOBS",
                        "C123",
                        LISTING,
                        null,
                        ATTRIBUTION,
                        ATTRIBUTION_URL,
                        LICENCE,
                        DISCLAIMER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void nhsMetadataMustBeCompleteAndCannotCarryQueryOrFragmentData() {
        AuthoritativeJobSourcePolicy policy =
                new AuthoritativeJobSourcePolicy("LIVE");

        assertThatThrownBy(() -> policy.normalize(
                        "NHS_JOBS",
                        "C123",
                        LISTING + "?from=browser",
                        null,
                        ATTRIBUTION,
                        ATTRIBUTION_URL,
                        LICENCE,
                        DISCLAIMER))
                .isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> policy.normalize(
                        "NHS_JOBS",
                        "C123",
                        LISTING,
                        null,
                        ATTRIBUTION,
                        ATTRIBUTION_URL,
                        LICENCE,
                        "NHS Jobs is endorsed."))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("approved NHS Jobs attribution");
    }
}
