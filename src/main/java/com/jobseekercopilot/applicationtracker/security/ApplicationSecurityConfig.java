package com.jobseekercopilot.applicationtracker.security;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ApplicationSecurityConfig {

    @Bean
    FilterRegistrationBean<ApplicationServiceIdentityFilter> disableContainerRegistration(
            ApplicationServiceIdentityFilter identityFilter) {
        FilterRegistrationBean<ApplicationServiceIdentityFilter> registration =
                new FilterRegistrationBean<>(identityFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            ApplicationServiceIdentityFilter serviceIdentityFilter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED",
                                        "Valid authentication is required."))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        response,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "ACCESS_DENIED",
                                        "Access is denied.")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/internal/system-data/**")
                        .hasAuthority(ApplicationAuthorities.ENVIRONMENT_DATA)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications")
                        .hasAnyAuthority(
                                ApplicationAuthorities.USER,
                                ApplicationAuthorities.PRODUCER)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/applications/*/document-replacements")
                        .hasAnyAuthority(
                                ApplicationAuthorities.USER,
                                ApplicationAuthorities.PRODUCER)
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/applications/*/document-replacements/*")
                        .hasAnyAuthority(
                                ApplicationAuthorities.USER,
                                ApplicationAuthorities.PRODUCER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/**")
                        .hasAnyAuthority(
                                ApplicationAuthorities.USER,
                                ApplicationAuthorities.PRODUCER,
                                ApplicationAuthorities.READER)
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/applications/*/document-reference")
                        .hasAnyAuthority(
                                ApplicationAuthorities.USER,
                                ApplicationAuthorities.PRODUCER)
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/applications/*/document-replacements/**")
                        .hasAuthority(ApplicationAuthorities.PRODUCER)
                        .requestMatchers("/api/v1/applications/**")
                        .hasAuthority(ApplicationAuthorities.USER)
                        .anyRequest().denyAll())
                .addFilterBefore(serviceIdentityFilter, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        response,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED",
                                        "Valid authentication is required.")))
                .build();
    }

    @Bean
    JwtDecoder applicationJwtDecoder(
            @Value("${application-tracker.security.jwk-set-uri}") String jwkSetUri,
            @Value("${application-tracker.security.issuer}") String issuer,
            @Value("${application-tracker.security.audience}") String audience) {
        validateConfiguration(jwkSetUri, issuer, audience);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                requiredAudience(audience),
                requiredAccessToken()));
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt ->
                List.of(new SimpleGrantedAuthority(ApplicationAuthorities.USER)));
        return converter;
    }

    private static OAuth2TokenValidator<Jwt> requiredAudience(String audience) {
        return token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : invalidToken();
    }

    private static OAuth2TokenValidator<Jwt> requiredAccessToken() {
        return token -> token.getSubject() != null
                        && !token.getSubject().isBlank()
                        && "access".equals(token.getClaimAsString("token_type"))
                ? OAuth2TokenValidatorResult.success()
                : invalidToken();
    }

    private static OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Access token validation failed.", null));
    }

    static void validateConfiguration(String jwkSetUri, String issuer, String audience) {
        try {
            URI uri = URI.create(jwkSetUri);
            if (!List.of("http", "https").contains(uri.getScheme())
                    || !uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || issuer == null
                    || issuer.isBlank()
                    || audience == null
                    || audience.isBlank()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Application Tracker JWT verification configuration is invalid");
        }
    }

    private static void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
