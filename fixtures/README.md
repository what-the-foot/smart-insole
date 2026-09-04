# 기능 테스트 Fixture

이 폴더의 데이터는 **하드웨어가 없는 상태에서 API·DB·실시간 UI·분석 파이프라인을 검증하기 위한 합성 데이터**입니다. 실제 사람의 측정값이나 임상 기준이 아니며 질환 판단 근거로 사용하면 안 됩니다.

## 공통 예시 식별자

```text
Session: 5803f871-9fca-4a7f-a2c7-9b567a92a6cf
Left device: b4b96290-ad73-42d9-ae21-1446f1258861
Right device: 64eb539f-4b48-44f6-bb30-d26861463ca6
Receiver: RECEIVER-PC-001
```

Frame batch 본문에는 session ID가 없으며 요청 경로에 넣습니다.

```text
POST /internal/v1/measurement-sessions/5803f871-9fca-4a7f-a2c7-9b567a92a6cf/frame-batches
```

실제 테스트에서는 DB에 생성된 session/device ID로 fixture를 치환하거나 Mock Receiver CLI 인자로 전달하세요. 실제 Receiver API Key는 fixture에 저장하지 않습니다.

## 파일

| 파일 | 목적 |
|---|---|
| `frame-batch-normal.json` | 양발 정상 수신 경로 |
| `frame-batch-left-asymmetry.json` | 왼발 값이 상대적으로 큰 합성 입력 |
| `frame-batch-duplicate.json` | 같은 device/sequence 중복 |
| `frame-batch-sequence-gap.json` | 의도된 sequence 누락 |
| `frame-batch-sensor-stuck.json` | 왼발 한 센서가 고정 고값 4095에 머무는 입력 |
| `frame-batch-right-disconnected.json` | 오른발 프레임이 없는 batch |
| `frame-batch-out-of-order.json` | 배열 내 도착 순서가 뒤바뀐 batch |
| `frame-batch-six-sensor.json` | 양발 6센서 기기의 정상 수신 경로 |
| `realtime-bilateral.json` | 양발 실시간 메시지 예시 |
| `realtime-right-disconnected.json` | 오른발이 `null`인 메시지 예시 |
| `manifest.json` | 목적과 최소 기대 결과의 기계 판독 목록 |

## 사용 원칙

- 원본 파일을 테스트 도중 변경하지 않습니다.
- 날짜와 값이 고정되어 테스트가 결정적이어야 합니다.
- 중복·gap·out-of-order는 의도된 시나리오입니다.
- 패턴 임계값은 아직 임상 확정값이 아니므로, 특정 질환 결과를 기대값으로 고정하지 않습니다.
- 6센서 경로는 `frame-batch-six-sensor.json`을 사용하며 테스트 기기의 `sensorCount`도 6으로 등록합니다.
- Fixture 추가 시 OpenAPI 또는 JSON Schema 검증을 함께 실행합니다.
