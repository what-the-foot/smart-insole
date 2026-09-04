package com.smartinsole.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.config.ReceiverProperties;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.global.error.ErrorResponse;
import com.smartinsole.global.error.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodySizeFilter extends OncePerRequestFilter {
    private final ReceiverProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RequestBodySizeFilter(ReceiverProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> request.getContentLengthLong() == 0;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long maximum = properties.maxRequestBytes();
        if (request.getContentLengthLong() > maximum) {
            reject(request, response);
            return;
        }
        try {
            filterChain.doFilter(new LimitedRequest(request, maximum), response);
        } catch (RequestBodyTooLargeException exception) {
            if (response.isCommitted()) throw exception;
            reject(request, response);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.PAYLOAD_TOO_LARGE;
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, code.defaultMessage(), Map.of(
                "maxRequestBytes", properties.maxRequestBytes()), TraceIdFilter.get(request), Instant.now(clock)));
    }

    public static boolean causedByLimit(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RequestBodyTooLargeException) return true;
        }
        return false;
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maximum;
        private ServletInputStream stream;

        private LimitedRequest(HttpServletRequest request, long maximum) {
            super(request);
            this.maximum = maximum;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new LimitedServletInputStream(super.getInputStream(), maximum);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maximum;
        private long consumed;

        private LimitedServletInputStream(ServletInputStream delegate, long maximum) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) add(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) add(read);
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = delegate.skip(count);
            if (skipped > 0) add(skipped);
            return skipped;
        }

        private void add(long count) throws RequestBodyTooLargeException {
            consumed += count;
            if (consumed > maximum) throw new RequestBodyTooLargeException();
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }

    public static class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException() {
            super("Request body exceeds the configured byte limit");
        }
    }
}
