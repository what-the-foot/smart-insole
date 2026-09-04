package com.smartinsole.global.error;

import java.util.Map;

public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;
    private final Map<String, String> headers;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage(), Map.of());
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, Map.of());
    }

    /**
     * @param headers response headers that accompany the error body, e.g. {@code Retry-After} and
     *                {@code X-Batch-Disposition} on a 409 SESSION_NOT_MEASURING
     */
    public BusinessException(ErrorCode code, String message, Map<String, Object> details,
                             Map<String, String> headers) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
        this.headers = Map.copyOf(headers);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
