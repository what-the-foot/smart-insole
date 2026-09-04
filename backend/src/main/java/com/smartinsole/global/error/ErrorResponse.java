package com.smartinsole.global.error;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details,
                                   String traceId, Instant timestamp) {
        return new ErrorResponse(code.name(), message, details, traceId, timestamp);
    }
}
