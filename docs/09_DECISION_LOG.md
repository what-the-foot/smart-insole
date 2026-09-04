# 09. 결정 기록

- `ACCEPTED`: 구현 기준
- `PROPOSED`: 검토 중
- `DEFERRED`: MVP 이후

## DEC-001 저장소 — ACCEPTED
백엔드·프론트는 모노레포 권장. 별도 저장소면 계약 파일을 동일하게 유지합니다.

## DEC-002 백엔드 — ACCEPTED
Java 21 + Spring Boot + Gradle. 일반 CRUD JPA, 원본 대량 입력 JDBC Batch.

## DEC-003 프론트 — ACCEPTED
React + TypeScript + Vite. TanStack Query, Axios, STOMP. 기존 일관된 스택은 유지합니다.

## DEC-004 DB — ACCEPTED
MySQL + Flyway.

## DEC-005 센서 수 — ACCEPTED
기본 8, 기기 설정으로 6 지원. 배열 길이는 device.sensorCount와 같아야 합니다. DB는 최대 8개 컬럼을 둡니다.

## DEC-006 속도 — ACCEPTED
원본 100Hz, Receiver HTTP 100~200ms 배치, UI 10~20Hz.

## DEC-007 시간 — ACCEPTED
실제 순서는 `deviceTimeMs`와 `sequence`, `receivedAt`은 운영 추적. 서버는 UTC.

## DEC-008 원본 — ACCEPTED
ADC 원본 불변. 보정·필터·특징은 별도.

## DEC-009 멱등성 — ACCEPTED
`(session_id, device_id, sequence_no)` UNIQUE. 중복은 count 후 무시.

## DEC-010 인증 — ACCEPTED
사용자 JWT Bearer, Receiver API Key, STOMP 인증과 세션 소유권. Refresh Token은 후속 가능.

## DEC-011 실시간 — ACCEPTED
WebSocket + STOMP. 원본 저장소가 아님. 재연결은 topic을 먼저 재구독한 뒤 REST
snapshot을 조회하며, 구독 이후 새 메시지가 도착했다면 늦은 snapshot으로 덮어쓰지 않습니다.

## DEC-012 분석 — ACCEPTED
MVP는 규칙 기반. 품질→보정→필터→이벤트→특징→규칙→추천. 머신러닝 분리는 DEFERRED.

## DEC-013 표현 — ACCEPTED
질환 확정 금지. 중립 패턴 코드 사용.

## DEC-014 히트맵 — ACCEPTED
양발 동시, layout 좌표, 한쪽 끊김 시 다른 발 유지.

## DEC-015 운동 — ACCEPTED
패턴 코드와 추천 코드를 백엔드가 매핑. 치료 보장 금지.

## DEC-016 계약 우선 — ACCEPTED
OpenAPI와 Realtime JSON Schema가 단일 기준. 계약→백엔드→프론트→통합.

## DEC-017 UI — PROPOSED
신뢰감 있는 재활·운동 대시보드. 기존 디자인 시스템 우선.

## DEC-018 원본 보존 — DEFERRED
보존 기간·압축·삭제는 실제 운영 요구 후 결정.

## DEC-019 센서 좌표계 — ACCEPTED
발 로컬 좌표는 `x=0` 내측→`x=1` 외측, `y=0` 발가락→`y=1` 뒤꿈치로
정의합니다. 착용자 기준 양발 화면에서는 왼발 센서·CoP·윤곽을 수평 반전합니다.

## DEC-020 기록·추천 상세 계약 — ACCEPTED
운동 가이드는 코드별 상세 API로 직접 조회하고, 기록은 날짜·상태·최소
품질 점수·패턴 필터와 주요 패턴을 서버 pagination 계약에서 제공합니다.

## DEC-021 기본 보정 — ACCEPTED
하드웨어 없는 `SIMULATED` MVP는 기기 등록 시 `identity-v1` 기능 검증용 보정을
생성합니다. 이 프로필은 개인화·임상 보정을 의미하지 않으며, 실제 기기 보정
워크플로는 하드웨어 프로토콜 확정 후 별도 버전으로 추가합니다.

## DEC-022 비밀번호 byte 한계 — ACCEPTED
BCrypt에 전달하는 비밀번호는 문자 수 제한과 별도로 UTF-8 72 byte 이하인지 먼저
검사합니다. 초과 입력은 예외나 자동 절단 대신 `400 INVALID_REQUEST`로 명시적으로
거절하며 OpenAPI의 `x-maxUtf8Bytes`에 같은 제약을 기록합니다.

## DEC-023 분석 결과 버전 호환성 — ACCEPTED
특징 계산 의미가 바뀌면 `algorithmVersion`을 올립니다. `rule-v1.1.0`에서 유효 걸음 수,
중족부·전족부 비율, 최대 압력, 평균 CoP를 추가합니다. 기존 `rule-v1.0.0` 결과에 없던
값은 `null`로 반환하여 실제 0과 구분하고, 저장된 과거 결과를 새 의미로 재해석하지 않습니다.
