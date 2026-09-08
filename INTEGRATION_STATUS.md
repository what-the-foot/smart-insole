# INTEGRATION_STATUS

세 저장소(펌웨어, BLE 수신기, 백엔드·프론트엔드)의 통합 상태를 개선 방안(2026-09-03 최종 통합안)의 단계 기준으로 기록한다.
실제로 실행한 검증만 PASS로 적고, 실행하지 않은 항목은 NOT RUN으로 남긴다.

마지막 갱신: 2026-09-04

## 1. 저장소와 커밋 범위

| 모듈 | 저장소 | 브랜치 | 기준 커밋(baseline) | 현재 커밋 | 커밋 수 |
|---|---|---|---|---|---|
| 펌웨어 | https://github.com/what-the-foot/firmware | main | `95e60b1` | `ded01ec` | 9 |
| BLE 수신기 | https://github.com/what-the-foot/ble-gateway | main | `5697d35` | `6f88b55` | 18 |
| 백엔드·프론트엔드 | https://github.com/what-the-foot/smart-insole | main | `f30b81a` | `ef49f22` + 이 문서 커밋 | 27 |

- 기준 커밋은 개선 전 소스를 그대로 넣은 첫 커밋이다. 이후 커밋은 개선 방안의 작업 단위(FW-*, GW-*, BE-*, FE-*, C-*)별로 나누었다.
- 수신기 `contracts/PINS.json`은 백엔드 `contracts/openapi.yaml` 1.1.0(커밋 `4668e98`)을 원본 그대로 vendoring해 만든 핀 37개를 담는다.

## 2. 단계별 상태

| 단계 | 내용 | 상태 | 근거 |
|---|---|---|---|
| 0 | Git 초기화, 계약 핀 | 완료 | 세 저장소 `git status --short` 빈 출력, `scripts/check_contract_pins.py` exit 0 (핀 37개) |
| 1 | 계약 1.1·백엔드 | 완료 (Docker 통합 테스트 제외) | `validate_contracts.py` 14항목 PASS, `gradlew test` 74 PASS / 0 fail / 1 skipped. MySQL Testcontainers 항목(`MySqlIntegrationTest`)은 Docker가 없어 skipped. 하드웨어 MTU 실측(병렬 A)은 NOT RUN |
| 2 | 수신기 백엔드 어댑터 | 완료 | pytest 789 PASS, ruff·mypy strict PASS. openapi-1.1 serializer, X-Receiver-Key, loopback http 플래그, 409 disposition RETRY/DROP, Outbox v3(DISCARDED), SessionCoordinator(WAITING→MEASURING→ENDED), heartbeat, receiver-status, 계약 테스트 17개 |
| 3 | 수신기 BLE v1 + 펌웨어 소형 수정 | 완료 (보드 검증 제외) | 펌웨어 unittest 91 PASS, `arduino-cli compile --profile xiao_nrf52840_sense` exit 0. 골든 벡터(Frame A/B/Status) 펌웨어 `tests/fixtures/ble-v1` ↔ 수신기 파서 바이트 일치. 보드에서 Control 연속 write·`0x06` 후 sequence=0 확인은 NOT RUN |
| 4 | 레이아웃·프론트·mock E2E | 완료 (mock E2E 실행 제외) | V6 `layout-s01s08-v1` seed, FE-1~FE-9 커밋, `npm run lint`(0 경고)·`npm run test -- --run`(20 files, 125 tests)·`npm run build`·`npm run api:check` PASS, 결과 화면 금지어('최대 압력'·'CoP'·'정상') 회귀 테스트 PASS. `scripts/e2e_gateway_mock.py` 60초 실행은 MySQL·백엔드 실행 환경이 없어 NOT RUN |
| 5 | 실기기 양발 30분 | NOT RUN | 실기기·보드 미연결. 실측 후 `VALIDATION_RESULTS.md`와 이 문서에 수치·커밋 SHA를 기록한다 |
| 6 | v2 단일 패킷(조건부)·결과 용어 | 부분 완료 | 결과 용어(V7, rule-v1.2.0 관찰 단계·결과 코드 6종·occurrenceRate, FE-3 화면)는 완료. v2 단일 패킷·조각·6/8 가변은 MTU 실측(43 이상) 전까지 미착수(D-015, OD-010) |

## 3. 모듈별 변경 요약

### 펌웨어 (`what-the-foot/firmware`, 0.2.0)

- Control 명령 큐와 `START_AND_SYNC(0x06)`, 명령 집합 `{0x00,0x01,0x02,0x06}` (D-014)
- 측정 100Hz / BLE 전송 50Hz 데시메이션 (D-020), BLE는 RAW 전용 (D-018)
- Status 16B(배터리 mV·%, streaming, fw, sync_epoch, device_serial) (D-016), Diagnostics 20B 특성
- 페어링 없음, 이름 `SMART-INSOLE-L/R`, 제조사 데이터 광고 (D-019), IMU burst read
- 골든 벡터 `tests/fixtures/ble-v1/*.packet`, `tools/export_golden_vectors.py`, 파서·수신기 도구·문서 갱신

### BLE 수신기 (`what-the-foot/ble-gateway`)

- `protocol/` 패키지(BLE v1 Frame A/B, Status, Diagnostics 파서, ParserRegistry)
- `adapters/ble/`(FrameAssembler, DeviceLink 상태기계, Bleak 소스), ParseWorker, SequenceUnwrapper(u16→u32, RESET 이어 붙임)
- `adapters/backend/openapi_v1_1.py` serializer, session_client, 응답 분류(HALT/DISCARD/deferral), Outbox v3
- SessionCoordinator·heartbeat·receiver-status, CSV 기록, FootAligner, Diagnostics 메트릭, Outbox retention
- 계약 테스트(vendored openapi 1.1.0), 골든 벡터 라운드트립, `contracts/PINS.json`·`scripts/check_contract_pins.py`

### 백엔드 (`what-the-foot/smart-insole`/backend)

- openapi 1.1.0: Frame Batch 1.1(protocolVersion, receivedAt, dataMode, calibrated, imuAvailable, accelMg, gyroDps10, batchId), 수신기용 세션 목록·조회, receiver-status, heartbeat battery/firmware
- V5 프레임 메타·ADC·수신기 컬럼, V6 `layout-s01s08-v1`, V7 결과 용어
- adcMax(기본 4095) 세션 단위 적용, 409 disposition RETRY/DROP, u32 sequence·wrap 의심, sourceType 기본 DEVICE, sampleRateHz {50,100}
- rule-v1.2.0(ObservationLevel, occurrenceRate, 결과 코드 6종, 센서 비율), STOMP `TOKEN_EXPIRED` ERROR 프레임
- `scripts/e2e_gateway_mock.py`, 1.1 fixture, FrameBatchContractTest, 결정 기록 DEC-024..032

### 프론트엔드 (`what-the-foot/smart-insole`/frontend)

- 계약 1.1 타입 재생성과 `api:check` 절차(FE-7)
- 용어 정리(FE-1), 실시간 신호·센서 비율(FE-2), 관찰 단계·발생 비율·센서 비율 막대(FE-3), 50/100Hz 선택·SIMULATED 표시(FE-4)
- 세션 피크 유지(FE-5), 세션 만료 배너·재로그인·`TOKEN_EXPIRED` 매핑(FE-6), 배지·품질 플래그·세션 ID 복사(FE-8), 8센서 기본·6센서 등록 차단·adcMax/배터리 표시(FE-9)

## 4. 검증 결과 (2026-09-04)

| 모듈 | 검증 | 결과 |
|---|---|---|
| 펌웨어 | `python -m unittest discover -s tests` | PASS (91) |
| 펌웨어 | `arduino-cli compile --profile xiao_nrf52840_sense` | PASS (exit 0) |
| 펌웨어 | 실기기 BLE·30분 내구·배터리 보정·MTU 실측 | NOT RUN |
| 수신기 | `pytest` / `ruff check` / `mypy src` | PASS (789) / PASS / PASS |
| 수신기 | `scripts/check_contract_pins.py` | PASS (37 pins) |
| 수신기 | 펌웨어 골든 벡터 라운드트립 | PASS (Frame A/B/Status 바이트 동일) |
| 수신기 | 실제 BLE 장치, 실제 백엔드 E2E | NOT RUN |
| 백엔드 | `gradlew test --offline` (JDK 21) | PASS (74, 1 skipped: Docker 없음) |
| 백엔드 | `python scripts/validate_contracts.py` | PASS (14) |
| 백엔드 | 실제 MySQL 8.0(로컬 서비스)에서 `bootRun` 기동, Flyway V1~V7 적용, Hibernate validate (2026-09-08) | PASS. 첫 기동에서 SMALLINT 컬럼 6개와 엔티티 int 매핑 불일치로 validate가 실패해 `@JdbcTypeCode(SMALLINT)`로 수정 |
| 백엔드 | `local` 프로필 시드 계정 생성과 `POST /api/v1/auth/signin` (2026-09-08) | PASS (200, Bearer 토큰 발급) |
| 프론트엔드 | `npm run lint` / `npm run test -- --run` / `npm run build` / `npm run api:check` | PASS (0 경고 / 125 tests / build / diff 없음) |
| 전체 | `scripts/e2e_gateway_mock.py` 60초 | NOT RUN (MySQL·백엔드 실행 환경 필요) |
| 전체 | 실기기 양발 30분 | NOT RUN |

## 5. 하드웨어 실측이 필요한 결정과 미착수 항목

- MTU 실측(`client.mtu_size` 43 이상 여부) → v2 단일 40B 패킷·조각 폴백·6/8 가변 도입 여부 (FW-1~FW-4, D-015, OD-010)
- 30분 실측 → 50Hz/100Hz 전송 확정(D-020), `ble_tx_blocked = 0`·`ble_write_max_us ≤ 2000` 합격 판정, 배터리 1점 보정(D-016)
- 센서 좌표(x, y 0-1), 무부하 잡음·센서 균일성 허용 범위, 관찰 단계별 발생 비율 기준은 규약 페이지에서 확정 필요
- 실기기 스케일(0-4095) 히트맵 밝기 검증(FE-3 실측)
- 프론트엔드 기존 10개 파일은 prettier 미적용 상태로 두었다(이번 작업 범위 밖)

## 6. 다음 단계

1. 보드 2대(L/R)로 수신기 `--mode ble` 5분 실측: 연결·구독·Control write·MTU 출력 확인
2. 30분 양발 실측 후 수치와 커밋 SHA를 `VALIDATION_RESULTS.md`(펌웨어)와 이 문서에 기록
3. MTU 결과에 따라 6단계 v2 항목 착수 여부 결정, 노션 결정 기록 갱신
4. Docker 환경에서 `MySqlIntegrationTest`와 `scripts/e2e_gateway_mock.py` 실행
