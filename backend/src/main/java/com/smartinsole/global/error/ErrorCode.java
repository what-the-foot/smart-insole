package com.smartinsole.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "JSON 요청을 읽을 수 없습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다."),
    RECEIVER_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Receiver 인증에 실패했습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 자원을 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    SERIAL_NUMBER_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 등록된 기기 일련번호입니다."),
    INVALID_SESSION_STATE(HttpStatus.CONFLICT, "현재 세션 상태에서는 요청을 수행할 수 없습니다."),
    SESSION_NOT_MEASURING(HttpStatus.CONFLICT, "현재 측정 중인 세션이 아닙니다."),
    ANALYSIS_FAILED(HttpStatus.CONFLICT, "분석을 완료하지 못했습니다."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 자원입니다."),
    SEMANTIC_VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "요청의 의미 검증에 실패했습니다."),
    INVALID_SENSOR_LAYOUT(HttpStatus.UNPROCESSABLE_ENTITY, "센서 배치가 기기 설정과 일치하지 않습니다."),
    INVALID_DEVICE_SELECTION(HttpStatus.UNPROCESSABLE_ENTITY, "측정 기기 선택이 올바르지 않습니다."),
    UNSUPPORTED_SCHEMA_VERSION(HttpStatus.UNPROCESSABLE_ENTITY, "지원하지 않는 schemaVersion입니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "허용된 요청 크기를 초과했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "요청 메서드를 지원하지 않습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "요청 미디어 형식을 지원하지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
