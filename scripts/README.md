# 검증·Mock·통합 도구

Python 3.10 이상을 사용합니다. HTTP 전송은 표준 라이브러리를 사용하고, 계약 및 E2E snapshot 검증 의존성은 정확한 버전으로 고정합니다.

```bash
python -m pip install -r scripts/requirements-contracts.txt
```

STOMP 프레이밍과 URL 비밀정보 마스킹은 서버 없이도 단위 테스트할 수 있습니다.

```bash
python -m unittest discover -s scripts -p "test_*.py"
```

PowerShell에서는 각 `.sh`와 같은 이름의 `.ps1` 래퍼를 사용할 수 있습니다.

## 계약과 fixture 검증

```bash
./scripts/validate-contracts.sh
```

```powershell
.\scripts\validate-contracts.ps1
```

`validate_contracts.py`는 다음을 실패 코드와 함께 검사합니다.

- OpenAPI 3.0.3 / `info.version` 1.1.0 파싱, 필수 operation(수신기 세션 조회·receiver-status 포함), 고유 `operationId`
- 모든 local `$ref` 해석
- 공통 enum(ObservationLevel·DataMode·ReceiverUploadState 포함)과 실시간 enum 일치
- 회원가입·로그인 비밀번호의 UTF-8 72-byte BCrypt 계약
- 계약 1.1 필드 정책: `sensorValues` 상한 4095, `sequence` u32, `sampleRateHz` [50, 100], `sourceType` 기본 DEVICE,
  1.1 프레임 선택 필드, `SensorPoint.label`, 수신기 세션·상태 스키마, 관찰 단계 필드
- Draft 2020-12 실시간 schema와 날짜·UUID format을 포함한 예시 검증
- OpenAPI `FrameBatchRequest`에 대한 모든 frame fixture 검증(4095 스케일, 4096 거부)
- 정상·비대칭·중복·gap·stuck·한쪽 발·out-of-order·6센서·1.1 실기기 fixture 의도 확인
- manifest(`fixtureVersion` 1.1, `adcMax` 4095)의 누락·고아 case와 fixture credential 필드 확인

기계 판독 결과가 필요하면 `python scripts/validate_contracts.py --json`을 사용합니다.

## 전체 검증

```bash
./scripts/verify-all.sh
./scripts/verify-all.sh --e2e
./scripts/verify-all.sh --gateway-e2e
```

```powershell
.\scripts\verify-all.ps1
.\scripts\verify-all.ps1 -E2E
.\scripts\verify-all.ps1 -GatewayE2E
```

실행 순서는 계약 → backend `test`/`check` → frontend `api:generate`/`lint`/`test`/`build` → 선택 API E2E → 선택 gateway mock E2E입니다. backend wrapper, frontend package/잠금 파일, 명령 또는 하위 단계가 없거나 실패하면 전체 명령도 실패합니다. 두 E2E는 기본적으로 명시적인 `[SKIP]`이며 `--e2e`/`-E2E`/`RUN_E2E=1`, `--gateway-e2e`/`-GatewayE2E`/`RUN_GATEWAY_E2E=1`로 활성화합니다.

## Mock Receiver

Mock Receiver는 fixture를 **schemaVersion 1.1** 배치(`batchId`, 프레임별 `receivedAt`, `protocolVersion`, `dataMode` RAW, `calibrated` false, IMU가 없으면 `imuAvailable` false·벡터 생략)로 보냅니다. 전송 전에 `GET /internal/v1/measurement-sessions/{id}`로 세션을 조회해 다음을 확정합니다.

- 세션이 `MEASURING`인지, `sourceType`이 `SIMULATED`인지(DEVICE 세션은 `--allow-device-session` 없이는 거부)
- LEFT/RIGHT device ID 기본값(명시하면 세션 배정과 일치해야 함)과 fixture 센서 수 일치
- `sampleRateHz`(50/100). fixture는 10 ms(100 Hz) 간격으로 작성되어 있으므로 50 Hz 세션에는 `deviceTimeMs`를 20 ms 간격으로 재조정해 `SAMPLE_RATE_MISMATCH`를 피합니다.

재시도는 동일 payload를 다시 보내므로 서버의 멱등성 계약을 전제로 합니다. Key는 출력하지 않으며 모든 로그를 `SIMULATED`로 표시합니다. 응답 count 합계가 전송 frame 수와 다르거나 reject가 하나라도 있으면 실패하고, 409는 `details.disposition`(RETRY/DROP)을 함께 출력합니다. 거부 동작을 의도적으로 확인하는 음성 fixture에만 `--allow-rejected`를 사용합니다.

```bash
python scripts/mock-receiver/mock_receiver.py \
  --session-id SESSION_UUID \
  --receiver-key RECEIVER_KEY \
  --fixture fixtures/frame-batch-normal.json
```

지원 환경변수: `SMART_INSOLE_BASE_URL`, `SMART_INSOLE_SESSION_ID`, `SMART_INSOLE_LEFT_DEVICE_ID`, `SMART_INSOLE_RIGHT_DEVICE_ID`, `SMART_INSOLE_RECEIVER_KEY`. `--schema-version 1.0`은 1.1 필드를 제거한 레거시 배치를 보냅니다. `--repeat`는 원본 파일을 바꾸지 않고 sequence와 device time을 이동하여 더 긴 합성 입력을 만듭니다. `--dry-run`은 HTTP 없이 치환·batch 크기·첫 프레임 키를 확인하며, `--skip-session-check`는 조회 없이 명시한 device ID로 보냅니다.

## API E2E smoke

이 스모크는 기존 REST 전체 흐름에 더해 실제 WebSocket/STOMP 연결을 엽니다. 소유자 A의
인증 구독과 JSON Schema 메시지 수신, 사용자 B의 A 세션 topic 구독 거부, 연결 중단 뒤
재연결 및 새 accepted frame 수신, LEFT frame을 계속 보내는 동안 RIGHT만 timeout되어
`RIGHT_DEVICE_DISCONNECTED`가 표시되고 LEFT는 연결 상태를 유지하는지 확인합니다.

WebSocket 주소는 기본적으로 `SMART_INSOLE_BASE_URL`의 `/ws`에서 유도합니다. 별도 주소나
Origin이 필요하면 `SMART_INSOLE_WS_URL`, `SMART_INSOLE_WS_ORIGIN`을 사용합니다. 제한 시간은
`--stomp-timeout-seconds`, `--foot-disconnect-timeout-seconds`로 조절할 수 있습니다. 백엔드의
허용 Origin 목록에는 `SMART_INSOLE_WS_ORIGIN` 값이 포함되어야 합니다. 토큰, 비밀번호,
Receiver Key는 출력하지 않습니다.

```bash
SMART_INSOLE_RECEIVER_KEY=... ./scripts/e2e-smoke.sh
```

합성 계정으로 가입/로그인 → 양발 기기(`layout-s01s08-v1`) → 세션 생성(`sourceType: SIMULATED`, 100 Hz)/시작 → fixture 수신 → REST 실시간 snapshot의 JSON Schema·양발·sequence 검증 → 종료 → 결과 polling(`rule-v1.2.0`, `observationSummary` 6종) → 추천 상세 → 필터 기록 확인을 수행합니다. 6센서 fixture는 활성 6센서 seed가 없으므로 `--sensor-layout-version`을 명시해야 합니다. `--run-id`가 같으면 동일 합성 계정과 기기를 재사용할 수 있습니다. 삭제 API가 계약에 없으므로 생성된 smoke 데이터는 자동 삭제하지 않습니다.

## Gateway mock E2E (하드웨어 없는 전 구간)

`e2e_gateway_mock.py`는 MySQL과 백엔드(`bootRun`)가 떠 있고 BLE 수신기 패키지(`smart-insole-ble-gateway`)의 CLI가
설치되어 있을 때만 실행할 수 있습니다. 기본 검증 체인에는 포함되지 않으며 `verify-all --gateway-e2e`/`-GatewayE2E`로만 실행합니다.

```bash
SMART_INSOLE_RECEIVER_KEY=... python scripts/e2e_gateway_mock.py \
  --gateway-command "python -m smart_insole_gateway.cli" --gateway-cwd ../smart-insole-ble-gateway \
  --duration-seconds 60 --replay
```

흐름: 가입 → `SMART-INSOLE-{L|R}-{hex8}` 시리얼로 `layout-s01s08-v1` 기기 등록 → 세션 생성(`sourceType: SIMULATED`, 50 Hz) → start →
수신기용 세션 조회·목록(`X-Receiver-Key`) 대조 → STOMP 구독(스키마 검증, 백그라운드 수집) → 수신기
`run --mode mock --session-id ... --duration-seconds N --metrics-json run.json`(env `openapi-1.1`, loopback http, `X-Receiver-Key`) →
선택 재생 실행(`--mock-script REPLAY_LAST`) → complete → 결과 polling.

판정: STOMP 메시지 ≥ 0.8 × publish-hz × duration 전부 스키마 PASS, 수신기 `MetricsSnapshot`의
`outbox_pending == 0`·`batches_terminal_failed == 0`·`outbox_terminal_failed == 0`·모든 큐 `overflows == 0`,
수신기 accepted가 `2 × sampleRateHz × duration`의 ±10% 이내이며 백엔드 실시간 snapshot의 `lastSequence`로 추정한 프레임 수와도 ±10% 이내,
재생 실행 시 duplicates ≥ 3·백엔드 cursor 불변, 결과 `COMPLETED`·`rule-v1.2.0`·`observationSummary` 6종·세션 `SIMULATED`.
수신기 환경변수 이름은 수신기 `settings.py`를 따르며 `--gateway-env KEY=VALUE`, `--gateway-key-env`, `--gateway-base-url-env`로 덮어쓸 수 있습니다.
백엔드 `received_frame_count`는 API로 노출되지 않으므로 실시간 snapshot의 `lastSequence`(START_AND_SYNC 후 0부터)로 추정합니다.

## 장시간 수신 관찰

이미 `MEASURING`인 합성 세션에 대해 실행합니다.

```bash
python scripts/observe_long_run.py \
  --session-id SESSION_UUID \
  --left-device-id LEFT_DEVICE_UUID \
  --right-device-id RIGHT_DEVICE_UUID \
  --receiver-key RECEIVER_KEY \
  --duration-seconds 60 \
  --output tmp/long-run.json
```

고정 seed로 양발 약 200 frame/s(`--sample-rate-hz` 50이면 100 frame/s)를 12비트(0..4095) 스케일로 생성하고 요청 지연, 처리 count, 실제 wall time과 처리량을 기록합니다. `--sample-rate-hz`는 세션의 `sampleRateHz`와 같아야 합니다. 결과는 관찰값이며 SLA 또는 임상 성능을 주장하지 않습니다. `--dry-run`으로 네트워크 없이 결정적 생성 경로를 확인할 수 있습니다.
