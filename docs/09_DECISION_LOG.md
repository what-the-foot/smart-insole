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

## DEC-024 수신기 계약 1.1 단일 변경 — ACCEPTED
수신기→백엔드 변경(프레임별 `receivedAt`, `protocolVersion`, `dataMode`, `calibrated`,
`imuAvailable`, IMU 벡터, `flags`, `batchId`, 세션 조회 API, heartbeat 확장, receiver-status)은
`schemaVersion 1.1`·`openapi 1.1.0` 한 번으로 확정합니다. `additionalProperties: false`와
`fail-on-unknown-properties`는 유지하므로 값이 없는 키는 생략하고, 1.0 배치에 1.1 필드가 오면
프레임 단위 `SCHEMA_FIELD_NOT_ALLOWED`로 거절합니다. 1.0 배치는 계속 허용하며 이후 필드 추가는
optional만 허용합니다. 인증은 `X-Receiver-Key`를 그대로 씁니다.

## DEC-025 ADC 4095 단일 스케일 — ACCEPTED
`devices.adc_max`(등록 허용값 4095만)와 세션 생성 시 `measurement_sessions.adc_max` 스냅샷이
검증 상한·실시간 정규화·분석 정규화·포화 휴리스틱의 유일한 기준입니다. 자바 코드에 65535
상수는 없고, V5 이전 행만 SQL에서 65535로 백필하여 레거시 SIMULATED 데이터의 재현성을
보존합니다. `allow-legacy-adc-max` 같은 우회 옵션은 두지 않으며
`app.analysis.adc-max-value`는 세션 값이 없을 때의 기본값으로만 남깁니다.
접촉 임계값은 센서당 3.75(8센서 30.0)의 제안값이며 무부하·직립 실측 후 확정합니다.

## DEC-026 센서 레이아웃 layout-s01s08-v1 — ACCEPTED
펌웨어 순서 S01..S08(index = S번호 − 1 = MUX 채널)을 V6에서 seed하고 `SensorPoint.label`을
유일한 확장 키로 둡니다. 좌표는 도면 확정 전 제안값(±0.05 보정 예정)입니다.
`layout-v1`/`layout-v1-6`은 FK 보존을 위해 `active=false`로만 바꾸고 조회 API는 비활성
레이아웃도 반환하며 등록만 차단합니다. 6센서 seed는 어떤 두 센서를 빼는지, 엄지(S08)
유무, MUX 매핑을 하드웨어에서 한 번에 결정할 때까지 만들지 않습니다.

## DEC-027 sequence u32와 gap 산술 — ACCEPTED
백엔드는 수신기가 펼친 단조 u32(상한 4294967295)만 받습니다. 누락 수는 발별
`first/last sequence`로 Σ(last − first + 1) − 수신의 O(1) 산술로 유지하고, 세션 완료 시
저장 행 기준 `countSequenceGaps`로 1회 대조합니다. sequence가 설정 거리(기본 60000) 이상
감소했는데 `deviceTimeMs`는 증가하면 `SEQUENCE_WRAP_SUSPECTED`(감점 10)를 붙이며,
protocolVersion 2 이상(네이티브 u32)에서는 비활성입니다. wrap이 의심된 세션은 행 순서가
모호하므로 완료 시 대조 대신 산술값을 유지합니다.

## DEC-028 sourceType 명시·sampleRateHz 50/100 — ACCEPTED
`sourceType`은 자동 판별하지 않고 요청 필드로 받되 기본값은 `DEVICE`, 시뮬레이터(mock
receiver, e2e 스크립트, 개발용 UI)만 `SIMULATED`를 명시합니다. `receiverId`는 신뢰 기준이
아닙니다. `sampleRateHz`는 50(BLE 전송률, 측정 100Hz 분주) 또는 100만 허용하고 세션 값이
전송률의 단일 출처이며, `deviceTimeMs` 차분 중앙값이 ±30%를 벗어나면
`SAMPLE_RATE_MISMATCH`를 붙입니다.

## DEC-029 409 disposition — ACCEPTED
`SESSION_NOT_MEASURING`의 `details.disposition`이 수신기 처리의 1차 판별자입니다.
세션 CREATED면 `RETRY`(+`Retry-After`, 기본 2초), 그 외에는 `DROP`이며 DROP은 WARN
로그(sessionId, 프레임 수, receiverId)를 남깁니다. `X-Batch-Disposition` 헤더는 보조 정보이고
`disposition`이 없으면 수신기가 `currentStatus`로 폴백합니다. `BusinessException`에 응답
헤더 맵을 추가했을 뿐 공통 오류 본문은 바뀌지 않습니다.

## DEC-030 rule-v1.2.0 관찰 단계 — ACCEPTED
패턴 코드는 `MEDIAL_LOAD_TENDENCY, LATERAL_LOAD_TENDENCY, LEFT_RIGHT_ASYMMETRY,
LOW_HALLUX_SIGNAL, FOREFOOT_LOAD_TENDENCY, REARFOOT_LOAD_TENDENCY` 6종으로 고정합니다.
창은 유효 걸음(접촉 구간)이고 `LEFT_RIGHT_ASYMMETRY`는 `receiver_received_at`(없으면 배치
`received_at`→index) 순의 좌우 창 쌍입니다. `ObservationLevel`은 발생 비율
0.20/0.60과 최소 창 4로 정하며(모두 제안값), `patterns`에는 PARTIALLY/REPEATEDLY만
저장하고 `observationSummary`는 6종 전체를 담습니다. `HIGH_MIDFOOT_LOAD`·`SHORT_CONTACT_TIME`은
제거하고 `LOW_DATA_QUALITY`는 `dataQuality.flags`로 옮기며 `REMEASURE_GUIDE`는 품질 점수
임계로 추천합니다. 센서별 share(접촉 프레임 평균, 합 100)를 `pressureDistribution`에
넣고, 이전 버전 결과의 새 필드는 `null`로 둡니다(DEC-023).

## DEC-031 STOMP 만료 처리 — ACCEPTED
구독 중 CONNECT 토큰이 만료되면 outbound 메시지를 조용히 버리지 않고 STOMP `ERROR`
프레임(`message:TOKEN_EXPIRED`)을 한 번 보내고 연결을 끊습니다. 만료 토큰의
CONNECT/SUBSCRIBE도 같은 메시지로 거절합니다. 프론트는 이를 AUTH_EXPIRED로 매핑해
재로그인 후 재구독합니다. 서버 측 토큰 리프레시 API는 후속입니다.

## DEC-032 하드웨어 없는 gateway E2E 판정 지표 — ACCEPTED
`scripts/e2e_gateway_mock.py`는 수신기 `MetricsSnapshot`의 실제 필드명(`outbox_pending`,
`batches_terminal_failed`, `outbox_terminal_failed`, 큐 `overflows`)과 백엔드 응답만으로
판정합니다. 백엔드 `received_frame_count`는 API로 노출하지 않으므로 실시간 snapshot의
발별 `lastSequence`(START_AND_SYNC 후 0부터)로 프레임 수를 추정해 ±10%로 비교합니다.
이 스크립트는 MySQL·백엔드·수신기 CLI가 모두 있을 때만 실행하며 기본 검증 체인에서는 SKIP입니다.

## 구현 중 계획과 달라진 점 (2026-09-04)
- V5 `pressure_frames` 메타 컬럼은 계획의 11개에 `flags`를 더한 12개(INSERT 26컬럼)입니다.
  `StoredPressureFrame`이 `flags`를 노출하려면 저장이 필요합니다.
- `RegisterDeviceRequest.adcMax` 오류는 `422 SEMANTIC_VALIDATION_FAILED`, `sampleRateHz` 오류도
  `422 SEMANTIC_VALIDATION_FAILED`로 통일했습니다(기기 선택 문제는 기존 `INVALID_DEVICE_SELECTION`).
- 1.1 프레임 검증 코드에 계획 목록 외 `INVALID_PROTOCOL_VERSION`(0 이하)을 추가했습니다.
- `flags` 비트와 `dataMode`는 품질 플래그 `FSR_ERROR_REPORTED`(감점 10)·`IMU_ERROR_REPORTED`·
  `BATTERY_LOW_REPORTED`·`FILTERED_DATA_MODE`(감점 5)로 드러나며, 프론트 FE-8 라벨 목록과 일치합니다.
- ApiFlow/MySQL 통합 테스트는 fixture가 발당 접촉 구간 1개라 `app.analysis.min-observation-windows=1`
  테스트 프로퍼티를 사용합니다(계획의 대안 중 하나).
- `MySqlIntegrationTest`(Testcontainers)는 이 환경에 Docker가 없어 SKIP되었습니다. 코드는
  `applied >= 7`, 1.1 왕복, `layout-s01s08-v1`, `rule-v1.2.0`으로 갱신했습니다.
- `scripts/e2e_gateway_mock.py`는 수신기 환경변수 이름을 `--gateway-env`로 덮어쓸 수 있게 두었습니다.
  수신기 `settings.py`의 최종 이름과 대조가 필요합니다.
