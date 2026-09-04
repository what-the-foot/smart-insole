package com.smartinsole.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.config.ReceiverProperties;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.global.error.ErrorResponse;
import com.smartinsole.global.error.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ReceiverApiKeyFilter extends OncePerRequestFilter {
    private final ReceiverProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReceiverApiKeyFilter(ReceiverProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Receiver-Key");
        boolean valid = supplied != null && properties.apiKey() != null
                && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                properties.apiKey().getBytes(StandardCharsets.UTF_8));
        if (valid) {
            filterChain.doFilter(request, response);
            return;
        }
        ErrorCode code = ErrorCode.RECEIVER_UNAUTHORIZED;
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, code.defaultMessage(), Map.of(),
                TraceIdFilter.get(request), Instant.now(clock)));
    }
}
