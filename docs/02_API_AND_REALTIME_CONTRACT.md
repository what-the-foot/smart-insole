# 02. REST API와 실시간 계약

## 계약 원칙

- REST 단일 기준: `contracts/openapi.yaml`
- 실시간 단일 기준: `contracts/realtime-message.schema.json`

변경 순서:

```text
계약 → 예시·문서 → 백엔드 → 프론트 타입·호출 → 통합 테스트
```

## 공통 값

```text
사용자 API: /api/v1
Receiver API: /internal/v1
WebSocket: /ws
FootSide: LEFT | RIGHT
SessionStatus: CREATED | MEASURING | PROCESSING | COMPLETED | CANCELLED | FAILED
SourceType: DEVICE | SIMULATED
```

서버 시각은 ISO-8601 UTC, 내부 식별자는 UUID를 사용합니다. `deviceTimeMs`는 실제 간격 계산용이며 `receivedAt`은 서버 운영 추적용입니다.

## 인증

사용자:

```http
Authorization: Bearer {access-token}
```

Receiver:

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
    "currentStatus": "COMPLETED"
  },
  "traceId": "6ad2da0687e34c19",
  "timestamp": "2026-09-02T07:30:00Z"
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
- `GET /api/v1/sensor-layouts/{version}`

### Measurement
- `POST /api/v1/measurement-sessions`
- `POST /api/v1/measurement-sessions/{id}/start`
- `POST /api/v1/measurement-sessions/{id}/complete`
- `POST /api/v1/measurement-sessions/{id}/cancel`
- `GET /api/v1/measurement-sessions`
- `GET /api/v1/measurement-sessions/{id}`
- `GET /api/v1/measurement-sessions/{id}/realtime-snapshot`
- `GET /api/v1/measurement-sessions/{id}/result`

### Receiver
- `POST /internal/v1/measurement-sessions/{id}/frame-batches`
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
  "serialNumber": "INSOLE-L-001",
  "displayName": "내 왼발 인솔",
  "footSide": "LEFT",
  "sensorCount": 8,
  "sensorLayoutVersion": "layout-v1",
  "firmwareVersion": "0.1.0"
}
```

등록·목록 응답은 현재 측정에 사용할 활성 보정 버전을 `activeCalibrationVersion`으로
반환합니다. 활성 보정이 없으면 `null`이며 측정 세션 생성이 거절됩니다.

## 세션 생성

```json
{
  "leftDeviceId": "b4b96290-ad73-42d9-ae21-1446f1258861",
  "rightDeviceId": "64eb539f-4b48-44f6-bb30-d26861463ca6",
  "sampleRateHz": 100,
  "memo": "실내 평지 보행"
}
```

응답:

```json
{
  "sessionId": "5803f871-9fca-4a7f-a2c7-9b567a92a6cf",
  "status": "CREATED",
  "leftDeviceId": "b4b96290-ad73-42d9-ae21-1446f1258861",
  "rightDeviceId": "64eb539f-4b48-44f6-bb30-d26861463ca6",
  "sampleRateHz": 100,
  "sourceType": "SIMULATED",
  "createdAt": "2026-09-02T07:10:00Z"
}
```

## Frame Batch

```json
{
  "schemaVersion": "1.0",
  "receiverId": "RECEIVER-PC-001",
  "sentAt": "2026-09-02T07:11:10.200Z",
  "frames": [
    {
      "deviceId": "b4b96290-ad73-42d9-ae21-1446f1258861",
      "footSide": "LEFT",
      "sequence": 10241,
      "deviceTimeMs": 102410,
      "sensorValues": [120, 245, 511, 803, 612, 301, 120, 80]
    }
  ]
}
```

검증:

- schema 지원
- Receiver 인증
- 세션 MEASURING
- 기기가 세션에 배정됨
- footSide 일치
- 배열 길이 = device.sensorCount이며 정확히 6개 또는 8개
- ADC 범위
- sequence/deviceTime 음수 금지
- 최대 배치 200 기본

응답:

```json
{
  "acceptedCount": 19,
  "duplicateCount": 2,
  "rejectedCount": 1,
  "rejections": [
    {
      "frameIndex": 7,
      "code": "INVALID_SENSOR_COUNT",
      "message": "기기 센서 수와 전달된 배열 길이가 다릅니다."
    }
  ],
  "lastSequenceByDevice": {
    "b4b96290-ad73-42d9-ae21-1446f1258861": 10260
  },
  "receivedAt": "2026-09-02T07:11:10.420Z"
}
```

인증 실패, 존재하지 않는 세션, 지원하지 않는 schema는 전체 요청 실패입니다. 개별 프레임 문제는 정책상 부분 거절할 수 있습니다.

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

실시간 `sensorValues`는 원본 ADC가 아니라 0~100 표시용 상대값입니다.

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

완료 결과:

```json
{
  "sessionId": "5803f871-9fca-4a7f-a2c7-9b567a92a6cf",
  "status": "COMPLETED",
  "algorithmVersion": "rule-v1.1.0",
  "dataQuality": {
    "score": 92,
    "level": "GOOD",
    "missingFrameRate": 0.003,
    "flags": []
  },
  "gaitSummary": {
    "validStepCount": 20,
    "cadence": 108.2,
    "leftContactTimeMs": 642.0,
    "rightContactTimeMs": 608.0,
    "symmetryIndex": 5.3
  },
  "pressureDistribution": {
    "leftMedialRatio": 0.61,
    "leftLateralRatio": 0.39,
    "rightMedialRatio": 0.58,
    "rightLateralRatio": 0.42,
    "leftHeelRatio": 0.35,
    "rightHeelRatio": 0.34,
    "leftMidfootRatio": 0.25,
    "rightMidfootRatio": 0.26,
    "leftForefootRatio": 0.40,
    "rightForefootRatio": 0.40,
    "leftPeakPressure": 88.4,
    "rightPeakPressure": 91.2,
    "leftMeanCoP": {"x": 0.42, "y": 0.67},
    "rightMeanCoP": null
  },
  "patterns": [
    {
      "code": "LEFT_RIGHT_ASYMMETRY",
      "severity": "CAUTION",
      "title": "좌우 접촉 시간 차이",
      "message": "왼발과 오른발의 접촉 시간 차이가 반복적으로 관찰되었습니다.",
      "evidence": "유효 걸음 20회 중 15회에서 설정 기준 이상의 차이가 나타났습니다."
    }
  ],
  "recommendations": [
    {
      "code": "ANKLE_STABILITY_BASIC",
      "title": "기본 발목 안정화 운동",
      "summary": "균형 유지와 발목 주변 근육 사용을 돕는 기초 운동입니다.",
      "durationMinutes": 10
    }
  ],
  "disclaimer": "본 결과는 의료 진단이 아니며, 통증이 지속되면 전문가의 평가가 필요합니다.",
  "createdAt": "2026-09-02T07:16:03Z"
}
```

`validStepCount`는 분석에 실제 사용된 접촉 이벤트 수입니다. 전족부 비율은
`FOREFOOT`과 `TOE` 영역의 합이며, 한 발의 뒤꿈치·중족부·전족부 비율 합은 압력이
있을 때 1입니다. `peakPressure`는 세션에서 관찰한 보정 후 0~100 센서 값의 최댓값이고,
`meanCoP`는 압력으로 가중한 0~1 평균 좌표입니다. 해당 발의 총압력이 0이거나 데이터가
없으면 평균 CoP는 `null`입니다. `rule-v1.1.0` 이전에 저장된 결과는 당시 알고리즘 버전의
불변 기록으로 유지하고 자동으로 새 의미로 재해석하지 않습니다. 이전 결과에 존재하지 않던
`validStepCount`, 중족부·전족부 비율, 최대 압력은 `null`로 반환하여 실제 0과 구분합니다.

## 추천 운동 상세

```text
GET /api/v1/recommendations/{code}
```

인증된 사용자에게 `title`, `purpose`, 순서가 있는 `instructions`,
`durationMinutes`, `cautionText`, `relatedPatternCodes`를 반환합니다. 추천 링크를
새로고침해도 세션 메모리에 의존하지 않고 다시 조회할 수 있습니다.

## 목록 페이지네이션

```text
GET /api/v1/measurement-sessions?page=0&size=20&status=COMPLETED&from=2026-09-01T00:00:00Z&to=2026-09-30T23:59:59Z&minQualityScore=80&patternCode=LEFT_RIGHT_ASYMMETRY
```

`status`, `from`, `to`, `minQualityScore`, `patternCode`는 모두 선택 필터입니다.
목록 항목의 `primaryPatternCode`는 최신 분석 결과에서 가장 먼저 정렬된 주요 패턴이며,
패턴이 없거나 분석 전이면 `null`입니다.

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

- [ ] OpenAPI
- [ ] Realtime schema
- [ ] 예시
- [ ] 백엔드 DTO·테스트
- [ ] 프론트 타입·Mock
- [ ] fixture
- [ ] 통합 테스트
- [ ] schemaVersion/하위 호환
