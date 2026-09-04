# 02. REST API와 실시간 계약

## 계약 원칙

- REST 단일 기준: `contracts/openapi.yaml` (현재 `1.1.0`)
- 실시간 단일 기준: `contracts/realtime-message.schema.json`
- BLE 와이어 기준은 펌웨어 저장소 `docs/ble-packet-v1.md`이며, 수신기는 이 저장소의 openapi.yaml을 SHA 핀으로 vendoring합니다.

변경 순서:

```text
계약 → 예시·문서 → 백엔드 → 프론트 타입·호출 → 통합 테스트
```

계약 1건은 PR 3개(① app: openapi+validate_contracts+fixture+DTO/테스트 → ② 수신기 vendoring·serializer·계약 테스트 → ③ 펌웨어 골든 벡터)로 반영합니다.

## 공통 값

```text
사용자 API: /api/v1
Receiver API: /internal/v1
WebSocket: /ws
FootSide: LEFT | RIGHT
SessionStatus: CREATED | MEASURING | PROCESSING | COMPLETED | CANCELLED | FAILED
SourceType: DEVICE | SIMULATED   (기본 DEVICE, 시뮬레이터만 SIMULATED 명시, 자동 판별 없음)
DataMode: RAW | FILTERED         (MVP BLE 와이어는 RAW 전용)
ReceiverUploadState: STREAMING | UPLOADING | UPLOAD_COMPLETE
ObservationLevel: NOT_OBSERVED | PARTIALLY_OBSERVED | REPEATEDLY_OBSERVED
ADC: 0..4095 (devices.adcMax, 세션 생성 시 스냅샷; 4095 = 포화)
sampleRateHz: 50 | 100 (UI 기본 50 = BLE 전송률, 측정 100Hz 분주)
sequence: 단조 u32 (0..4294967295, 수신기가 v1 u16을 펼침)
```

서버 시각은 ISO-8601 UTC, 내부 식별자는 UUID를 사용합니다. `deviceTimeMs`는 실제 간격 계산용, 배치 `receivedAt`은 서버 운영 추적용, 1.1 프레임별 `receivedAt`(`receiver_received_at`)은 좌우 정렬 기준입니다.

## 인증

사용자:

```http
Authorization: Bearer {access-token}
```

Receiver(모든 `/internal/v1/**`, 16바이트 이상 키):

```http
X-Receiver-Key: {receiver-api-key}
```

회원가입과 로그인 비밀번호는 BCrypt 입력 한계에 맞춰 UTF-8 인코딩 기준 72 byte
이하여야 합니다. 길이 초과는 해시 함수까지 전달하지 않고 `400 INVALID_REQUEST`로
거절합니다.

## 오류

```json
{
  "code": "SESSION_NOT_MEASURING",
  "message": "현재 측정 중인 세션이 아닙니다.",
  "details": {
    "currentStatus": "COMPLETED",
    "disposition": "DROP"
  },
  "traceId": "6ad2da0687e34c19",
  "timestamp": "2026-09-04T07:30:00Z"
}
```

| 상황 | HTTP |
|---|---:|
| 형식 오류 | 400 |
| 인증 실패 | 401 |
| 타인 자원 | 403 |
| 없음 | 404 |
| 상태 충돌 | 409 |
| 의미 검증 | 422 |
| 크기 초과 | 413 |
| 서버 오류 | 500 |

## 핵심 API

### Auth
- `POST /api/v1/auth/signup`
- `POST /api/v1/auth/signin`

### Device
- `GET /api/v1/devices`
- `POST /api/v1/devices`
- `GET /api/v1/sensor-layouts/{version}` (비활성 레이아웃도 반환, 등록만 활성 요구)

### Measurement
- `POST /api/v1/measurement-sessions`
- `POST /api/v1/measurement-sessions/{id}/start`
- `POST /api/v1/measurement-sessions/{id}/complete`
- `POST /api/v1/measurement-sessions/{id}/cancel`
- `GET /api/v1/measurement-sessions`
- `GET /api/v1/measurement-sessions/{id}`
- `GET /api/v1/measurement-sessions/{id}/realtime-snapshot`
- `GET /api/v1/measurement-sessions/{id}/result`

### Receiver (`X-Receiver-Key`)
- `GET /internal/v1/measurement-sessions?status=MEASURING&deviceSerial=...`
- `GET /internal/v1/measurement-sessions/{id}`
- `POST /internal/v1/measurement-sessions/{id}/frame-batches`
- `POST /internal/v1/measurement-sessions/{id}/receiver-status`
- `POST /internal/v1/devices/{id}/heartbeat`

## 로그인 응답

```json
{
  "tokenType": "Bearer",
  "accessToken": "masked-token",
  "expiresInSeconds": 3600,
  "user": {
    "userId": "7e95630d-6b53-4b1d-96f4-0acc7ab72e91",
    "email": "user@example.com",
    "name": "사용자",
    "createdAt": "2026-09-02T07:00:00Z"
  }
}
```

## 기기 등록

```json
{
  "serialNumber": "SMART-INSOLE-L-12345678",
  "displayName": "내 왼발 인솔",
  "footSide": "LEFT",
  "sensorCount": 8,
  "sensorLayoutVersion": "layout-s01s08-v1",
  "firmwareVersion": "0.2.0",
  "adcMax": 4095
}
```

- `serialNumber`는 `SMART-INSOLE-{L|R}-{serial u32 hex 8자리}` 규칙을 권장합니다. 수신기는 BLE 스캔 제조사 데이터로 같은 문자열을 만들어 세션 조회 응답과 대조합니다.
- `adcMax`는 생략 가능하며 4095만 허용합니다(다른 값은 `422 SEMANTIC_VALIDATION_FAILED`). V5 이전에 등록된 레거시 기기는 65535로 백필되어 있으며 새 등록에서는 만들 수 없습니다.
- `sensorLayoutVersion`은 활성 레이아웃이어야 합니다. `layout-v1`/`layout-v1-6`은 조회만 가능하고 등록은 `422 INVALID_SENSOR_LAYOUT`입니다. 활성 6센서 seed는 하드웨어 확정 전까지 없습니다.
- 응답 `DeviceResponse`는 `adcMax`, `lastBatteryPercent`, `lastBatteryMv`(heartbeat 반영, 없으면 `null`)와 `activeCalibrationVersion`을 포함합니다.

### 센서 배치

`GET /api/v1/sensor-layouts/layout-s01s08-v1`은 펌웨어 순서 S01..S08(index = S번호 − 1 = MUX 채널)의 8점을 `label`과 함께 반환합니다. 레거시 레이아웃의 `label`은 `null`입니다.

```json
{"index": 7, "label": "S08", "x": 0.36, "y": 0.12, "region": "TOE", "medialLateral": "MEDIAL"}
```

## 세션 생성

```json
{
  "leftDeviceId": "b4b96290-ad73-42d9-ae21-1446f1258861",
  "rightDeviceId": "64eb539f-4b48-44f6-bb30-d26861463ca6",
  "sampleRateHz": 50,
  "sourceType": "DEVICE",
  "memo": "실내 평지 보행"
}
```

- `sampleRateHz`는 50 또는 100(그 외 `422 SEMANTIC_VALIDATION_FAILED`). 전송률의 단일 출처이며 수신기는 세션 조회 값으로 자기 설정을 덮어씁니다.
- `sourceType`은 생략하면 `DEVICE`입니다. mock receiver·e2e 스크립트·개발용 UI만 `SIMULATED`를 명시합니다.
- 양발 기기의 `adcMax`가 같아야 하며(`422 INVALID_DEVICE_SELECTION`) 그 값이 세션 `adcMax`로 스냅샷됩니다.

응답:

```json
{
  "sessionId": "5803f871-9fca-4a7f-a2c7-9b567a92a6cf",
  "status": "CREATED",
  "leftDeviceId": "b4b96290-ad73-42d9-ae21-1446f1258861",
  "rightDeviceId": "64eb539f-4b48-44f6-bb30-d26861463ca6",
  "sampleRateHz": 50,
  "sourceType": "DEVICE",
  "adcMax": 4095,
  "createdAt": "2026-09-04T01:00:00Z",
  "receiverState": null,
  "receiverPendingBatches": null
}
```

`receiverState`/`receiverPendingBatches`는 수신기의 마지막 `receiver-status` 보고이며 보고 전이면 `null`입니다.

## 수신기용 세션 조회

수신기는 bootstrap에서 `GET /internal/v1/measurement-sessions/{sessionId}`를 1회 동기 호출해 기기 식별자·센서 수·adcMax·sampleRateHz를 확정하고, 이후 2초 간격으로 상태 전이만 폴링합니다.

```json
{
  "sessionId": "6f0e...", "status": "MEASURING", "sampleRateHz": 50, "sourceType": "DEVICE", "adcMax": 4095,
  "startedAt": "2026-09-04T01:00:00Z", "endedAt": null,
  "left":  {"deviceId": "a1b2...", "serialNumber": "SMART-INSOLE-L-12345678", "footSide": "LEFT",
            "sensorCount": 8, "sensorLayoutVersion": "layout-s01s08-v1", "adcMax": 4095, "firmwareVersion": "0.2.0"},
  "right": {"deviceId": "c3d4...", "serialNumber": "SMART-INSOLE-R-9abcdef0", "footSide": "RIGHT",
            "sensorCount": 8, "sensorLayoutVersion": "layout-s01s08-v1", "adcMax": 4095, "firmwareVersion": "0.2.0"},
  "receiverState": "STREAMING", "receiverPendingBatches": 0
}
```

- 404 `RESOURCE_NOT_FOUND`, 401 `RECEIVER_UNAUTHORIZED`.
- `GET /internal/v1/measurement-sessions?status=MEASURING&deviceSerial=...` → `{"items": [...]}` (빈 배열 허용, 최신순). `status`는 MEASURING만 허용하며 다른 값은 `400 INVALID_REQUEST`, 모르는 serial은 빈 목록입니다.

## Frame Batch (schemaVersion 1.0 / 1.1)

`POST /internal/v1/measurement-sessions/{sessionId}/frame-batches`, frames 최대 200, 본문 1MiB. `additionalProperties: false`와 백엔드 `fail-on-unknown-properties: true`가 유지되므로 값이 없는 키는 `null` 대신 생략합니다.

```json
{
  "schemaVersion": "1.1",
  "receiverId": "GATEWAY-DEV-001",
  "batchId": "0192a...-batch",
  "sentAt": "2026-09-04T01:02:03.456789Z",
  "frames": [
    {
      "deviceId": "a1b2...", "footSide": "LEFT", "sequence": 65537, "deviceTimeMs": 123456,
      "sensorValues": [100, 200, 300, 400, 500, 600, 700, 800],
      "protocolVersion": 1,
      "receivedAt": "2026-09-04T01:02:03.401234Z",
      "dataMode": "RAW", "calibrated": false, "imuAvailable": true,
      "accelMg": [10, -20, 995], "gyroDps10": [3, -4, 5]
    }
  ]
}
```

1.1 선택 필드: `protocolVersion`, `receivedAt`(프레임별 PC 수신 시각), `dataMode`, `calibrated`, `imuAvailable`, `accelMg`/`gyroDps10`(int16 3축, 함께만 허용), `flags`(protocolVersion 2 이상; bit0 FSR_ERROR, bit1 IMU_ERROR, bit2 BATTERY_LOW). v1(A/B 와이어)에서는 meta에서 `dataMode`/`calibrated`/`imuAvailable`만 채웁니다. `IMU_ERROR` 비트가 켜지면 백엔드가 `imuAvailable=false`로 정규화하며, flags·dataMode는 품질 플래그 `FSR_ERROR_REPORTED`/`IMU_ERROR_REPORTED`/`BATTERY_LOW_REPORTED`/`FILTERED_DATA_MODE`로 드러납니다.

검증(전체 요청 실패):

- schemaVersion ∈ {1.0, 1.1} (그 외 `422 UNSUPPORTED_SCHEMA_VERSION`)
- Receiver 인증(401), 세션 존재(404), 세션 MEASURING(409, 아래 disposition)
- receiverId·sentAt·frames 형식(400), 200 프레임 초과(413)

검증(프레임별 거절 코드):

| 코드 | 의미 |
|---|---|
| `INVALID_DEVICE_ID`, `DEVICE_NOT_ASSIGNED`, `INVALID_FOOT_SIDE`, `FOOT_SIDE_MISMATCH` | 기기·방향 불일치 |
| `INVALID_SEQUENCE` | 0..4294967295 밖 |
| `INVALID_DEVICE_TIME` | 음수 |
| `INVALID_SENSOR_COUNT` | 배열 길이 ≠ device.sensorCount |
| `INVALID_ADC_VALUE` | 0..세션 adcMax(4095) 밖 |
| `SCHEMA_FIELD_NOT_ALLOWED` | 1.0 배치에 1.1 필드 |
| `INVALID_PROTOCOL_VERSION`, `INVALID_RECEIVED_AT`, `INVALID_DATA_MODE`, `INVALID_FLAGS`, `INVALID_IMU` | 1.1 필드 값 오류 |

응답:

```json
{
  "acceptedCount": 19,
  "duplicateCount": 2,
  "rejectedCount": 1,
  "rejections": [
    {"frameIndex": 7, "code": "INVALID_SENSOR_COUNT", "message": "기기 센서 수와 전달된 배열 길이가 다릅니다."}
  ],
  "lastSequenceByDevice": {"b4b96290-ad73-42d9-ae21-1446f1258861": 10260},
  "receivedAt": "2026-09-04T01:02:03.520Z"
}
```

### 응답·오류 코드별 수신기 처리

- 200 → SUCCESS. rejectedCount > 0이어도 재전송은 무의미하므로 metrics·WARN만 남깁니다.
- 400 / 413 / 422 / 404 → NON_RETRYABLE(terminal).
- 401 → HALT 60초.
- 409 `SESSION_NOT_MEASURING` → `details.disposition`이 1차 판별자입니다. `RETRY`(세션 CREATED)면 `Retry-After` 초만큼 연기(attempt 미소비), `DROP`이면 배치를 폐기(DISCARD tombstone)합니다. 헤더 `X-Batch-Disposition`은 보조 정보이며 `disposition`이 없으면 `details.currentStatus`(CREATED→RETRY, 그 외→DISCARD)로 폴백합니다.
- 408 / 429 / 5xx → RETRYABLE(Retry-After 존중, 지수 백오프).

첫 배치의 `receiverId`는 세션에 기록됩니다.

## heartbeat·업로드 상태 보고

`POST /internal/v1/devices/{deviceId}/heartbeat` (204): 연결/해제 전이 즉시 + 5초 주기.

```json
{"receiverId": "GATEWAY-DEV-001", "observedAt": "2026-09-04T01:00:20Z", "connected": true,
 "batteryPercent": 80, "batteryMv": 3900, "firmwareVersion": "0.2.0", "rssi": -58}
```

`batteryPercent` 255(미보정)는 수신기가 `null`로 보냅니다. 늦게 도착한(observedAt이 이전보다 과거인) heartbeat는 무시되며, 반영된 heartbeat의 배터리·펌웨어 값은 `DeviceResponse.lastBatteryPercent/lastBatteryMv/firmwareVersion`으로 노출됩니다.

`POST /internal/v1/measurement-sessions/{sessionId}/receiver-status` (204):

```json
{"receiverId": "GATEWAY-DEV-001", "state": "UPLOADING", "pendingBatchCount": 3, "observedAt": "2026-09-04T01:05:00Z"}
```

MEASURING 세션에서만 허용(그 외 `409 SESSION_NOT_MEASURING`), best-effort(수신기 재시도 없음), 행 잠금 후 갱신하여 배치 ingest의 `@Version` 충돌을 막습니다. 세션 완료 시 마지막 보고가 `UPLOADING`이거나 `pendingBatchCount > 0`이면 품질 플래그 `RECEIVER_UPLOAD_INCOMPLETE`(감점 10)가 붙습니다.

## 품질 플래그

| 플래그 | 출처 |
|---|---|
| `SEQUENCE_GAP`, `OUT_OF_ORDER`, `DEVICE_TIME_JUMP` | sequence/deviceTimeMs 순서 |
| `SEQUENCE_WRAP_SUSPECTED` | sequence가 `app.ingestion.sequence-wrap-suspect-distance`(60000) 이상 감소했는데 deviceTimeMs는 증가(protocolVersion 2 이상은 비활성) |
| `SAMPLE_RATE_MISMATCH` | deviceTimeMs 차분 중앙값이 세션 주기에서 ±30% 이탈 |
| `SENSOR_STUCK_OR_SATURATED` | 한 센서가 세션 adcMax에 고정 |
| `LEFT/RIGHT_DATA_MISSING`, `LEFT/RIGHT_DATA_INCOMPLETE`, `LEFT/RIGHT_DEVICE_DISCONNECTED`, `INSUFFICIENT_DATA` | 커버리지·연결 |
| `RECEIVER_UPLOAD_INCOMPLETE` | receiver-status 보고 |
| `FSR_ERROR_REPORTED`, `IMU_ERROR_REPORTED`, `BATTERY_LOW_REPORTED`, `FILTERED_DATA_MODE` | 1.1 프레임 flags/dataMode |
| `LOW_DATA_QUALITY` | 분석 시 품질 점수 < `poor-quality-score-threshold`(60) |

gap 계산은 발별 `first/last sequence`로 O(1)이며(누락 = Σ(last−first+1) − 수신), 세션 완료 시 저장 행 기준으로 1회 대조합니다.

## WebSocket

```text
Endpoint: /ws
Topic: /topic/measurement-sessions/{sessionId}/pressure
```

STOMP CONNECT에서 JWT를 전달하고 구독 시 소유권을 검사합니다.

```json
{
  "schemaVersion": "1.0",
  "sessionId": "5803f871-9fca-4a7f-a2c7-9b567a92a6cf",
  "serverTime": "2026-09-02T07:11:10.500Z",
  "elapsedTimeMs": 10500,
  "status": "MEASURING",
  "left": {
    "connected": true,
    "lastSequence": 10241,
    "deviceTimeMs": 102410,
    "sensorValues": [12.1, 24.6, 51.0, 80.2, 61.3, 30.2, 12.0, 8.1],
    "totalPressure": 279.5,
    "cop": {"x": 0.42, "y": 0.73},
    "contactState": "CONTACT",
    "lastReceivedAt": "2026-09-02T07:11:10.490Z"
  },
  "right": null,
  "quality": {
    "score": 83,
    "level": "ACCEPTABLE",
    "flags": ["RIGHT_DEVICE_DISCONNECTED"]
  }
}
```

실시간 `sensorValues`는 원본 ADC가 아니라 세션 `adcMax` 기준 0~100 표시용 상대값입니다. 접촉 판정은 `app.analysis.contact-total-threshold-per-sensor`(3.75) × 센서 수(8센서 30.0)입니다. 프론트의 센서 share 표시(센서/전체합×100)는 표시 전용이며 임계 판단은 하지 않습니다.

토큰 만료: CONNECT 토큰이 구독 중 만료되면 서버는 메시지를 조용히 버리지 않고 STOMP `ERROR` 프레임(`message:TOKEN_EXPIRED`)을 한 번 보낸 뒤 연결을 끊습니다. 만료 토큰의 CONNECT/SUBSCRIBE도 같은 메시지로 거절되며, 프론트는 이를 AUTH_EXPIRED로 매핑해 재로그인 후 재구독합니다.

재연결:

1. WebSocket 복구
2. topic 재구독
3. REST realtime snapshot 조회
4. 구독 이후 수신한 메시지가 없을 때만 snapshot으로 화면 복구

## 종료와 결과

종료 후 세션은 `PROCESSING`입니다. 결과 준비 중에는 `202`를 기본으로 합니다.

```json
{
  "sessionId": "5803f871-9fca-4a7f-a2c7-9b567a92a6cf",
  "status": "PROCESSING",
  "message": "분석이 진행 중입니다."
}
```

완료 결과(`rule-v1.2.0`):

```json
{
  "sessionId": "5803f871-9fca-4a7f-a2c7-9b567a92a6cf",
  "status": "COMPLETED",
  "algorithmVersion": "rule-v1.2.0",
  "dataQuality": {"score": 92, "level": "GOOD", "missingFrameRate": 0.003, "flags": []},
  "gaitSummary": {"validStepCount": 21, "cadence": 108.2, "leftContactTimeMs": 642.0, "rightContactTimeMs": 608.0, "symmetryIndex": 5.3},
  "pressureDistribution": {
    "leftMedialRatio": 0.61, "leftLateralRatio": 0.39, "rightMedialRatio": 0.58, "rightLateralRatio": 0.42,
    "leftHeelRatio": 0.35, "rightHeelRatio": 0.34, "leftMidfootRatio": 0.25, "rightMidfootRatio": 0.26,
    "leftForefootRatio": 0.40, "rightForefootRatio": 0.40, "leftPeakPressure": 88.4, "rightPeakPressure": 91.2,
    "leftMeanCoP": {"x": 0.42, "y": 0.67}, "rightMeanCoP": null,
    "leftSensorSharePct": [18.2, 16.9, 7.1, 6.4, 14.8, 13.9, 12.7, 10.0],
    "rightSensorSharePct": [17.5, 16.4, 7.3, 6.6, 15.1, 14.2, 13.0, 9.9]
  },
  "patterns": [
    {
      "code": "LEFT_RIGHT_ASYMMETRY", "severity": "CAUTION",
      "title": "좌우 접촉 시간 차이",
      "message": "왼발과 오른발의 접촉 시간 차이가 관찰되었습니다.",
      "evidence": "좌우 걸음 쌍 21회 중 13회(62%)에서 기능 검증용 기준을 넘었습니다.",
      "observationLevel": "REPEATEDLY_OBSERVED", "occurrenceRate": 0.62, "observedCount": 13, "windowCount": 21
    }
  ],
  "observationSummary": [
    {"code": "MEDIAL_LOAD_TENDENCY", "observationLevel": "NOT_OBSERVED", "occurrenceRate": 0.05, "observedCount": 2, "windowCount": 42},
    {"code": "LATERAL_LOAD_TENDENCY", "observationLevel": "NOT_OBSERVED", "occurrenceRate": 0.0, "observedCount": 0, "windowCount": 42},
    {"code": "LEFT_RIGHT_ASYMMETRY", "observationLevel": "REPEATEDLY_OBSERVED", "occurrenceRate": 0.62, "observedCount": 13, "windowCount": 21},
    {"code": "LOW_HALLUX_SIGNAL", "observationLevel": "NOT_OBSERVED", "occurrenceRate": 0.0, "observedCount": 0, "windowCount": 42},
    {"code": "FOREFOOT_LOAD_TENDENCY", "observationLevel": "NOT_OBSERVED", "occurrenceRate": 0.1, "observedCount": 4, "windowCount": 42},
    {"code": "REARFOOT_LOAD_TENDENCY", "observationLevel": "NOT_OBSERVED", "occurrenceRate": 0.0, "observedCount": 0, "windowCount": 42}
  ],
  "recommendations": [
    {"code": "ANKLE_STABILITY_BASIC", "title": "기본 발목 안정화 운동", "summary": "균형 유지와 발목 주변 근육 사용을 돕는 기초 운동입니다.", "durationMinutes": 10}
  ],
  "disclaimer": "본 결과는 의료 진단이 아니며, 통증이 지속되면 전문가의 평가가 필요합니다.",
  "createdAt": "2026-09-04T07:16:03Z"
}
```

- 창(window) = 유효 걸음(접촉 구간). 발 단위 코드(`MEDIAL/LATERAL/FOREFOOT/REARFOOT_LOAD_TENDENCY`, `LOW_HALLUX_SIGNAL`)는 양발 창 전체를, `LEFT_RIGHT_ASYMMETRY`는 좌우 창 쌍(`receiver_received_at` 기준 정렬, 없으면 배치 `received_at`→index 순 폴백)을 셉니다. `LOW_HALLUX_SIGNAL`은 엄지(TOE·MEDIAL) 센서가 있는 레이아웃의 창만 셉니다.
- `occurrenceRate = observedCount / windowCount`. `partial-observation-rate`(0.20) 이상이면 `PARTIALLY_OBSERVED`, `repeated-observation-rate`(0.60) 이상이면 `REPEATEDLY_OBSERVED`, 창이 `min-observation-windows`(4) 미만이면 항상 `NOT_OBSERVED`. 창별 임계값: medial/lateral 0.60, forefoot 0.60, rearfoot 0.55, hallux share 5%, asymmetry 10%. 모두 임상 근거 없는 제안값이며 `app.analysis.*`로 설정합니다.
- `patterns`에는 `PARTIALLY_OBSERVED`/`REPEATEDLY_OBSERVED`만 들어가며(REPEATEDLY→CAUTION, PARTIALLY→INFO, 강한 순), `observationSummary`는 6종 코드 전체를 담습니다. 코드 집합은 정확히 `MEDIAL_LOAD_TENDENCY, LATERAL_LOAD_TENDENCY, LEFT_RIGHT_ASYMMETRY, LOW_HALLUX_SIGNAL, FOREFOOT_LOAD_TENDENCY, REARFOOT_LOAD_TENDENCY`입니다. `HIGH_MIDFOOT_LOAD`/`SHORT_CONTACT_TIME`은 제거되었고 `LOW_DATA_QUALITY`는 `dataQuality.flags`로만 노출되며 `REMEASURE_GUIDE`는 품질 점수 < 60일 때 추천됩니다.
- `leftSensorSharePct/rightSensorSharePct`는 접촉 프레임 평균의 센서별 share(센서/전체합×100, 레이아웃 index 순, 합 100)이며 접촉 프레임이 없으면 `null`입니다.
- `validStepCount`는 분석에 실제 사용된 접촉 이벤트 수입니다. 전족부 비율은 `FOREFOOT`과 `TOE` 영역의 합이며, `peakPressure`는 세션 adcMax 기준 0~100 센서 값의 최댓값, `meanCoP`는 압력 가중 0~1 평균 좌표입니다.
- 이전 알고리즘 버전 결과는 불변 기록으로 유지하고 재해석하지 않습니다. `rule-v1.2.0` 이전 결과의 `observationSummary`, 패턴의 `observationLevel/occurrenceRate/observedCount/windowCount`, `sensorSharePct`, `rule-v1.1.0` 이전의 `validStepCount`·중족부/전족부 비율·최대 압력은 `null`로 반환하여 실제 0과 구분합니다.

## 추천 운동 상세

```text
GET /api/v1/recommendations/{code}
```

인증된 사용자에게 `title`, `purpose`, 순서가 있는 `instructions`,
`durationMinutes`, `cautionText`, `relatedPatternCodes`를 반환합니다. `relatedPatternCodes`는 rule-v1.2.0 6종의 부분집합이며(`ANKLE_STABILITY_BASIC` → `LEFT_RIGHT_ASYMMETRY`, `BALANCED_FOOT_LOADING` → 나머지 5종), `REMEASURE_GUIDE`는 품질 점수로 추천되므로 비어 있습니다.

## 목록 페이지네이션

```text
GET /api/v1/measurement-sessions?page=0&size=20&status=COMPLETED&from=2026-09-01T00:00:00Z&to=2026-09-30T23:59:59Z&minQualityScore=80&patternCode=LEFT_RIGHT_ASYMMETRY
```

`status`, `from`, `to`, `minQualityScore`, `patternCode`는 모두 선택 필터입니다.
목록 항목의 `primaryPatternCode`는 최신 분석 결과에서 가장 먼저 정렬된 주요 패턴이며,
패턴이 없거나 분석 전이면 `null`입니다. `patternCode`는 저장된 패턴(PARTIALLY/REPEATEDLY)만 매칭합니다.

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

## 계약 변경 체크

- [ ] OpenAPI (`info.version`, `scripts/validate_contracts.py`의 REQUIRED_OPERATIONS/EXPECTED_ENUMS)
- [ ] Realtime schema
- [ ] 예시·fixture (`fixtures/manifest.json`)
- [ ] 백엔드 DTO·테스트 (`FrameBatchContractTest`)
- [ ] 프론트 타입 재생성(`npm run api:generate`)·exact-key 배열·Mock
- [ ] 수신기 vendored openapi·PINS·serializer 계약 테스트
- [ ] 통합 테스트
- [ ] schemaVersion/하위 호환 (1.0 배치는 계속 허용, 추가 필드는 optional만)
