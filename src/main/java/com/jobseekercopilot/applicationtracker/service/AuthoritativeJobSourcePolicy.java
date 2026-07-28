package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthoritativeJobSourcePolicy {

    static final String NHS_PROVIDER = "NHS_JOBS";
    static final String NHS_ATTRIBUTION_LABEL = "Vacancy source: NHS Jobs";
    static final String NHS_ATTRIBUTION_URL = "https://www.jobs.nhs.uk/";
    static final String NHS_LICENCE_URL =
            "https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/";
    static final String NHS_DISCLAIMER =
            "NHS Jobs does not endorse Job Seeker Copilot.";

    private final NhsJobsMode nhsJobsMode;

    public AuthoritativeJobSourcePolicy(
            @Value("${application-tracker.source.nhs-jobs.mode:LIVE}")
                    String nhsJobsMode) {
        try {
            this.nhsJobsMode =
                    NhsJobsMode.valueOf(nhsJobsMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException(
                    "NHS Jobs source mode must be LIVE or FIXTURE.", exception);
        }
    }

    SourceMetadata normalize(
            String provider,
            String externalJobId,
            String listingUrl,
            String applyUrl,
            String attributionLabel,
            String attributionSourceUrl,
            String licenceUrl,
            String disclaimer) {
        if (!NHS_PROVIDER.equals(provider)) {
            return new SourceMetadata(
                    optional(listingUrl),
                    optional(applyUrl),
                    optional(attributionLabel),
                    optional(attributionSourceUrl),
                    optional(licenceUrl),
                    optional(disclaimer));
        }

        String normalizedListing =
                requireApprovedVacancyUrl(listingUrl, externalJobId, "listingUrl");
        String normalizedApply = StringUtils.hasText(applyUrl)
                ? requireApprovedVacancyUrl(applyUrl, externalJobId, "applyUrl")
                : null;
        requireExact(attributionLabel, NHS_ATTRIBUTION_LABEL, "attributionLabel");
        requireExact(
                attributionSourceUrl, NHS_ATTRIBUTION_URL, "attributionSourceUrl");
        requireExact(licenceUrl, NHS_LICENCE_URL, "licenceUrl");
        requireExact(disclaimer, NHS_DISCLAIMER, "disclaimer");

        return new SourceMetadata(
                normalizedListing,
                normalizedApply,
                NHS_ATTRIBUTION_LABEL,
                NHS_ATTRIBUTION_URL,
                NHS_LICENCE_URL,
                NHS_DISCLAIMER);
    }

    private String requireApprovedVacancyUrl(
            String value, String externalJobId, String field) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidRequestException(field + " is required for NHS_JOBS.");
        }
        String normalized = value.trim();
        URI uri;
        try {
            uri = new URI(normalized);
        } catch (URISyntaxException exception) {
            throw invalidUrl(field);
        }
        String expectedHost = nhsJobsMode == NhsJobsMode.LIVE
                ? "www.jobs.nhs.uk"
                : "fixtures.jobseekercopilot.test";
        String expectedPath = nhsJobsMode == NhsJobsMode.LIVE
                ? "/candidate/jobadvert/" + externalJobId
                : "/nhs-jobs/jobadvert/" + externalJobId;
        boolean approved = "https".equalsIgnoreCase(uri.getScheme())
                && expectedHost.equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1
                && uri.getUserInfo() == null
                && expectedPath.equals(uri.getPath())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        if (!approved) {
            throw invalidUrl(field);
        }
        return uri.toASCIIString();
    }

    private InvalidRequestException invalidUrl(String field) {
        return new InvalidRequestException(
                field + " must be the authoritative NHS Jobs HTTPS vacancy URL for the configured source mode.");
    }

    private void requireExact(String value, String expected, String field) {
        if (!expected.equals(optional(value))) {
            throw new InvalidRequestException(
                    field + " must contain the approved NHS Jobs attribution.");
        }
    }

    private String optional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    enum NhsJobsMode {
        LIVE,
        FIXTURE
    }

    record SourceMetadata(
            String listingUrl,
            String applyUrl,
            String attributionLabel,
            String attributionSourceUrl,
            String licenceUrl,
            String disclaimer) {
    }
}
