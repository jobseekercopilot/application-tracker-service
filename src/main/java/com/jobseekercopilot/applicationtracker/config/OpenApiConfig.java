package com.jobseekercopilot.applicationtracker.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI applicationTrackerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Jobseeker Copilot - Application Tracker API")
                        .description("""
                                Service for tracking job applications, their generated documents, and application status.
                                
                                This service stores document references only (cvDocumentId, coverLetterDocumentId).
                                It does NOT store document contents.
                                
                                ApplicationStatus values:
                                - DOCUMENTS_GENERATED - Documents have been generated for the job
                                - APPLIED - Application has been submitted
                                - INTERVIEW - Interview stage
                                - UNSUCCESSFUL - Application was unsuccessful
                                - OFFER - Offer received
                                - ACCEPTED - Offer accepted
                                - REJECTED_BY_USER - Offer or opportunity rejected by the user
                                - WITHDRAWN - User withdrew their application
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Jobseeker Copilot"))
                        .license(new License()
                                .name("MIT")));
    }
}
