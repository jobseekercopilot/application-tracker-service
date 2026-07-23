package com.jobseekercopilot.applicationtracker.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String SERVICE_MDC_KEY = "serviceName";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final String serviceName;

    public CorrelationIdFilter(@Value("${spring.application.name:application-tracker-service}") String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (!StringUtils.hasText(correlationId)
                || !SAFE_CORRELATION_ID.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }

        String route = routeGroup(request);
        long startedAt = System.nanoTime();
        MDC.put(MDC_KEY, correlationId);
        MDC.put(SERVICE_MDC_KEY, serviceName);
        response.setHeader(HEADER_NAME, correlationId);

        try {
            log.info("service={} request started method={} route={}",
                    serviceName,
                    request.getMethod(),
                    route);
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("service={} request completed method={} route={} status={} durationMs={}",
                    serviceName,
                    request.getMethod(),
                    route,
                    response.getStatus(),
                    durationMs);
            MDC.remove(MDC_KEY);
            MDC.remove(SERVICE_MDC_KEY);
        }
    }

    private String routeGroup(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/applications")) {
            return "applications";
        }
        if (path.startsWith("/internal/system-data")) {
            return "system-data";
        }
        if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) {
            return "api-docs";
        }
        if (path.startsWith("/actuator")) {
            return "actuator";
        }
        return "other";
    }
}
