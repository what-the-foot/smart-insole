# 검증 보고서

검증일: 2026-09-04  
대상: `smart-insole-codex-kit` (계약 1.1 · 백엔드 1단계, 브랜치 `main`)  
환경: Windows 11, 번들 JDK 21 (`.tooling/jdk21`), Gradle 9.7.1 (offline), Python 3.10, Docker 없음

## 결과 요약

| 상태 | 검사 | 명령 | 결과 |
|---|---|---|---|
| PASS | 계약·fixture 검증 | `python scripts/validate_contracts.py` | 14 passed, 0 failed (OpenAPI 1.1.0, 19 operations, 146 local `$ref`, 9 enums, 9 frame fixtures on the 4095 scale, 11 manifest cases) |
| PASS | 스크립트 단위 테스트 | `python -m unittest discover -s scripts -p "test_*.py"` | 7 tests OK |
| PASS | 스크립트 컴파일·dry-run | `py_compile`, `mock_receiver.py --dry-run`(1.1 fixture, 50 Hz 재조정, `--repeat 2`), `observe_long_run.py --dry-run`, `e2e_gateway_mock.py --help` | 정상 종료 |
| PASS | 백엔드 테스트 (H2, `test` 프로필) | `gradlew.bat test --no-daemon --offline` | 74 tests, 0 failures, 0 errors, 1 skipped |
| SKIP | MySQL Testcontainers | `MySqlIntegrationTest` | `@Testcontainers(disabledWithoutDocker = true)` — 이 환경에 Docker가 없어 실행되지 않음. Flyway V5–V7과 MySQL 8.4 SQL은 실행으로 검증하지 못했습니다 |
| NOT RUN | API E2E smoke / gateway mock E2E | `scripts/e2e_smoke.py`, `scripts/e2e_gateway_mock.py` | MySQL·백엔드·수신기 CLI 필요 |
| PASS | 프론트엔드 (2026-09-04, 프론트 작업 후) | `npm run api:check`, `npm run lint`, `npm run test -- --run`, `npm run build` | api:check diff 0 · lint 0 errors/0 warnings · 20 files, 125 tests passed · build OK(빌드 후 schema.ts diff 0). `format:check`는 손대지 않은 기존 10개 파일만 미포맷(상세: `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md` 검증 기록) |

`gradlew test` 실행 클래스(74 tests): ApiFlowIntegrationTest, FrameBatchContractTest, ReceiverSessionControllerTest,
PressureFrameIngestionServiceTest, MeasurementServiceTest, DeviceServiceTest, RecommendationServiceTest,
QualityServiceTest, MeasurementSessionTest, HistoryLatestResultIntegrationTest, RuleBasedAnalyzerTest,
AnalysisCoordinatorTest, AnalysisResultQueryServiceTest, AnalysisRunnerTest, AnalysisJobStateServiceConcurrencyTest,
RealtimeServiceTest, RealtimeSnapshotStoreTest, StompAuthorizationInterceptorTest, DeviceHeartbeatTest,
AsyncConfigTest, SecurityPropertiesTest, JwtServiceTest, MySqlIntegrationTest(skipped).

## 1단계 완료 기준 대조 (계획 4장)

| 기준 | 상태 | 근거 |
|---|---|---|
| `validate_contracts.py` PASS | PASS | 14/14 |
| `gradlew test` PASS | PASS | 74/0/1 skipped |
| MySQL Testcontainers Flyway applied ≥ 5 | NOT RUN | 테스트는 `applied >= 7`로 갱신했으나 Docker 없음 |
| 1.0/1.1 배치 모두 200 | PASS | `FrameBatchContractTest`(fixtures/frame-batch-device-v1_1.json 24프레임 acceptedCount 24), `ApiFlowIntegrationTest`(1.0) |
| 4096 프레임 `INVALID_ADC_VALUE` | PASS | `FrameBatchContractTest`, `PressureFrameIngestionServiceTest` |
| 세션 조회 401/200 | PASS | `ReceiverSessionControllerTest` |
| complete 후 배치 409 DROP · CREATED 세션 409 RETRY(`Retry-After`) | PASS | `FrameBatchContractTest`, `ApiFlowIntegrationTest`, `ReceiverSessionControllerTest`, `PressureFrameIngestionServiceTest` |
| 자바 코드 `65535` 0건 | PASS | `grep -rn 65535 backend/src` → V5 SQL 백필 2건만 |
| sampleRateHz 50 세션 201 · 60은 422 | PASS | `FrameBatchContractTest`(50), `MeasurementServiceTest`(60 → SEMANTIC_VALIDATION_FAILED) |
| `GET /api/v1/sensor-layouts/layout-s01s08-v1` 8점·label S01..S08, `layout-v1` 조회 200·등록 422 | PASS | `FrameBatchContractTest`, `DeviceServiceTest` (seed 자체는 V6 SQL, MySQL 미실행) |
| rule-v1.2.0 `observationSummary` 6종·patterns 부분집합, 레거시 결과 500 없음, 폐기 코드 미출력 | PASS | `ApiFlowIntegrationTest`, `RuleBasedAnalyzerTest`, `AnalysisResultQueryServiceTest`, `RecommendationServiceTest` |
| STOMP `TOKEN_EXPIRED` ERROR 프레임 | PASS | `StompAuthorizationInterceptorTest` |

## 검증 범위의 한계

- Docker가 없어 MySQL 8.4 위의 Flyway V5–V7(`ALTER ... ADD COLUMN`, `UPDATE ... SET adc_max = 65535`, layout seed)과
  `ddl-auto: validate` 정합성은 실행으로 확인하지 못했습니다. H2 `create-drop` 프로필은 엔티티 기준으로만 검증합니다.
  CI 또는 로컬에서 Docker를 확보해 `MySqlIntegrationTest`를 실행해야 합니다.
- `scripts/e2e_gateway_mock.py`는 수신기 저장소의 CLI·환경변수 이름(`settings.py`)과 `MetricsSnapshot` 필드명을 전제로
  작성했으며 실행하지 않았습니다. 수신기 2단계 완료 후 `verify-all --gateway-e2e`로 검증합니다.
- 프론트엔드의 `schema.ts` 재생성, exact-key 배열, 기본 레이아웃, `sourceType`, 결과 화면 갱신은 이후 프론트 작업(FE-1~FE-9)에서
  반영·검증했습니다. 결과는 위 표와 `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md`의 검증 기록을 참조하세요.
- 접촉 임계값(센서당 3.75), 관찰 단계 비율(0.20/0.60, 최소 창 4), 창별 임계값(forefoot 0.60, rearfoot 0.55, hallux 5 %)은
  모두 제안값이며 실측·임상 근거가 없습니다.
