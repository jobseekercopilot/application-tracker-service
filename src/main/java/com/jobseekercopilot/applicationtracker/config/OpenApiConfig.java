package com.jobseekercopilot.applicationtracker.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI applicationTrackerOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        .addSecuritySchemes(
                                "serviceToken",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Service-Token"))
                        .addSecuritySchemes(
                                "environmentDataToken",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Environment-Data-Token")))
                .info(new Info()
                        .title("Jobseeker Copilot - Application Tracker API")
                        .description("""
                                Service for tracking job applications, their generated documents, and application status.
                                
                                This service validates and stores exact approved Document Store version
                                references. Current editable selections are separate from the immutable
                                versions and explicit omissions frozen only when an application
                                first enters APPLIED.
                                Atomic document-selection commands require explicit SELECTED or OMITTED
                                state for both optional slots, an expected record version and a durable
                                owner-scoped idempotency key.
                                Applying requires expectedVersion and Idempotency-Key, re-verifies
                                every selected exact version, and commits both frozen slot states,
                                timestamps and content-free events atomically.
                                Durable reconciliation verifies both reference sets against Document Store,
                                repairs only missing immutable metadata, and reports conflicts without
                                overwriting historical evidence.
                                Ordered Store lifecycle projections mark exact references archived,
                                deleted or purged. Purge keeps family/version identity and association
                                history while scrubbing complete hashes and evidence details.
                                It does NOT store document contents.
                                
                                ApplicationStatus values:
                                - SAVED - Job has been saved to the claimant's applications
                                - DOCUMENTS_GENERATED - Documents have been generated for the job
                                - APPLIED - Application has been submitted
                                - INTERVIEW - Interview stage
                                - UNSUCCESSFUL - Application was unsuccessful
                                - OFFER - Offer received
                                - ACCEPTED - Offer accepted
                                - REJECTED_BY_USER - Offer or opportunity rejected by the user
                                - WITHDRAWN - User withdrew their application
                                """)
                        .version("4.6.0")
                        .contact(new Contact()
                                .name("Jobseeker Copilot"))
                        .license(new License()
                                .name("Proprietary and confidential")))
                .servers(List.of(new Server()
                        .url("http://localhost:8088")
                        .description("Local development")));
    }

    @Bean
    public OpenApiCustomizer closedSystemDataSchemas() {
        return openApi -> List.of(
                        "SystemDataApplicationSeedRequest",
                        "SystemDataApplicationSeedRecord")
                .forEach(name -> {
                    var schema = openApi.getComponents().getSchemas().get(name);
                    if (schema != null) {
                        schema.setAdditionalProperties(false);
                    }
                });
    }
}
