package com.jobseekercopilot.applicationtracker.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class HttpDocumentStoreWorkflowClient
        implements DocumentStoreWorkflowClient {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String OWNER_HEADER = "X-Document-Owner";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String producerToken;

    @Autowired
    public HttpDocumentStoreWorkflowClient(
            RestTemplateBuilder builder,
            @Value("${services.document-store-service.base-url:}")
            String baseUrl,
            @Value("${services.document-store-service.producer-token:}")
            String producerToken) {
        this(
                builder
                        .setConnectTimeout(Duration.ofSeconds(2))
                        .setReadTimeout(Duration.ofSeconds(5))
                        .build(),
                baseUrl,
                producerToken);
    }

    HttpDocumentStoreWorkflowClient(
            RestTemplate restTemplate,
            String baseUrl,
            String producerToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.producerToken = producerToken;
    }

    @Override
    public void softDeleteGeneratedDocuments(
            String ownerId,
            UUID operationId,
            UUID applicationId,
            List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        if (!StringUtils.hasText(baseUrl)
                || !StringUtils.hasText(producerToken)) {
            throw new DocumentStoreWorkflowException(
                    "DOCUMENT_STORE_NOT_CONFIGURED", true, null);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SERVICE_TOKEN_HEADER, producerToken);
        headers.set(OWNER_HEADER, ownerId);
        Map<String, Object> request = Map.of(
                "operationId", operationId,
                "applicationId", applicationId,
                "documentIds", documentIds);
        try {
            restTemplate.exchange(
                    baseUrl + "/api/v1/documents/application-withdrawals",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    Void.class);
        } catch (HttpClientErrorException exception) {
            throw new DocumentStoreWorkflowException(
                    "DOCUMENT_CLEANUP_REJECTED", false, exception);
        } catch (HttpServerErrorException exception) {
            throw new DocumentStoreWorkflowException(
                    "DOCUMENT_STORE_UNAVAILABLE", true, exception);
        } catch (RestClientException exception) {
            throw new DocumentStoreWorkflowException(
                    "DOCUMENT_STORE_UNAVAILABLE", true, exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }
}
