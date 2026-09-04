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

- OpenAPI 3.0.3 파싱, 필수 operation, 고유 `operationId`
- 모든 local `$ref` 해석
- 공통 enum과 실시간 enum 일치
- 회원가입·로그인 비밀번호의 UTF-8 72-byte BCrypt 계약
- Draft 2020-12 실시간 schema와 날짜·UUID format을 포함한 예시 검증
- OpenAPI `FrameBatchRequest`에 대한 모든 frame fixture 검증
- 정상·비대칭·중복·gap·stuck·한쪽 발·out-of-order·6센서 의도 확인
- manifest의 누락·고아 case와 fixture credential 필드 확인

기계 판독 결과가 필요하면 `python scripts/validate_contracts.py --json`을 사용합니다.

## 전체 검증

```bash
./scripts/verify-all.sh
./scripts/verify-all.sh --e2e
```

```powershell
.\scripts\verify-all.ps1
.\scripts\verify-all.ps1 -E2E
```

실행 순서는 계약 → backend `test`/`check` → frontend `api:generate`/`lint`/`test`/`build` → 선택 API E2E입니다. backend wrapper, frontend package/잠금 파일, 명령 또는 하위 단계가 없거나 실패하면 전체 명령도 실패합니다. E2E는 기본적으로 명시적인 `[SKIP]`이며 `--e2e`, `-E2E`, 또는 `RUN_E2E=1`로 활성화합니다.

## Mock Receiver

Mock Receiver는 fixture의 LEFT/RIGHT device ID를 실행 중인 세션의 ID로 바꾸고, 한 발당 100Hz를 기준으로 100~200ms 단위 batch를 보냅니다. 재시도는 동일 payload를 다시 보내므로 서버의 멱등성 계약을 전제로 합니다. Key는 출력하지 않으며 모든 로그를 `SIMULATED`로 표시합니다.
응답 count 합계가 전송 frame 수와 다르거나 reject가 하나라도 있으면 실패합니다. 거부 동작을 의도적으로 확인하는 음성 fixture에만 `--allow-rejected`를 사용합니다. 서버 cursor는 항상 장치별 최댓값으로 병합하여 늦게 도착한 중복 응답이 진행률을 되돌리지 않습니다.

```bash
python scripts/mock-receiver/mock_receiver.py \
  --session-id SESSION_UUID \
  --left-device-id LEFT_DEVICE_UUID \
  --right-device-id RIGHT_DEVICE_UUID \
  --receiver-key RECEIVER_KEY \
  --fixture fixtures/frame-batch-normal.json
```

지원 환경변수: `SMART_INSOLE_BASE_URL`, `SMART_INSOLE_SESSION_ID`, `SMART_INSOLE_LEFT_DEVICE_ID`, `SMART_INSOLE_RIGHT_DEVICE_ID`, `SMART_INSOLE_RECEIVER_KEY`. `--repeat`는 원본 파일을 바꾸지 않고 sequence와 device time을 이동하여 더 긴 합성 입력을 만듭니다. `--dry-run`은 HTTP 없이 치환·batch 크기만 확인합니다.

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

합성 계정으로 가입/로그인 → 양발 기기 → 세션 생성/시작 → fixture 수신 → REST 실시간 snapshot의 JSON Schema·양발·sequence 검증 → 종료 → 결과 polling → 추천 상세 → 필터 기록 확인을 수행합니다. 6센서 fixture는 별도 지정이 없으면 `layout-v1-6`, 8센서는 `layout-v1`을 자동 선택합니다. `--run-id`가 같으면 동일 합성 계정과 기기를 재사용할 수 있습니다. 토큰·비밀번호·Receiver Key는 출력하지 않습니다. 삭제 API가 계약에 없으므로 생성된 smoke 데이터는 자동 삭제하지 않습니다.

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

고정 seed로 양발 약 200 frame/s를 생성하고 요청 지연, 처리 count, 실제 wall time과 처리량을 기록합니다. 결과는 관찰값이며 SLA 또는 임상 성능을 주장하지 않습니다. `--dry-run`으로 네트워크 없이 결정적 생성 경로를 확인할 수 있습니다.
