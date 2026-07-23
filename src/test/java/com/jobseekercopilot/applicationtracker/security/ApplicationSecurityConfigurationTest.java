package com.jobseekercopilot.applicationtracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApplicationSecurityConfigurationTest {

    private static final String PRODUCER = "producer-token-with-at-least-32-bytes";
    private static final String READER = "reader-token-with-at-least-32-bytes-value";
    private static final String ENVIRONMENT = "environment-token-with-at-least-32-bytes";

    @Test
    void serviceCredentialsResolveOnlyTheirOwnAuthority() {
        ApplicationSecurityCredentials credentials =
                new ApplicationSecurityCredentials(PRODUCER, READER, ENVIRONMENT);

        assertThat(credentials.authorityForServiceToken(PRODUCER))
                .contains(ApplicationAuthorities.PRODUCER);
        assertThat(credentials.authorityForServiceToken(READER))
                .contains(ApplicationAuthorities.READER);
        assertThat(credentials.authorityForServiceToken("wrong-token")).isEmpty();
        assertThat(credentials.matchesEnvironmentDataToken(ENVIRONMENT)).isTrue();
        assertThat(credentials.matchesEnvironmentDataToken(PRODUCER)).isFalse();
    }

    @Test
    void serviceCredentialsRejectMissingShortOrSharedTokens() {
        assertThatThrownBy(() ->
                new ApplicationSecurityCredentials("", READER, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application producer token must contain at least 32 bytes.");
        assertThatThrownBy(() ->
                new ApplicationSecurityCredentials("short", READER, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application producer token must contain at least 32 bytes.");
        assertThatThrownBy(() ->
                new ApplicationSecurityCredentials(PRODUCER, PRODUCER, ENVIRONMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application security tokens must be distinct.");
    }

    @Test
    void jwtVerificationConfigurationFailsClosed() {
        ApplicationSecurityConfig.validateConfiguration(
                "https://auth.example.test/.well-known/jwks.json",
                "issuer",
                "audience");

        assertThatThrownBy(() ->
                ApplicationSecurityConfig.validateConfiguration(
                        "file:///tmp/jwks.json",
                        "issuer",
                        "audience"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application Tracker JWT verification configuration is invalid");
        assertThatThrownBy(() ->
                ApplicationSecurityConfig.validateConfiguration(
                        "https://user@example.test/jwks",
                        "issuer",
                        "audience"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                ApplicationSecurityConfig.validateConfiguration(
                        "https://auth.example.test/jwks",
                        " ",
                        "audience"))
                .isInstanceOf(IllegalStateException.class);
    }
}
