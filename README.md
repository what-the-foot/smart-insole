# 스마트 인솔 재활 보조 MVP

양발 스마트 인솔의 원본 족압 데이터를 수집하고 실시간으로 시각화한 뒤, 규칙 기반 보행 패턴과 운동 가이드를 제공하는 모노레포입니다. 결과는 관찰된 패턴과 데이터 품질을 설명하며 의료 진단을 확정하지 않습니다.

기존 설계·계약 패키지를 기준으로 다음 실행 코드를 함께 제공합니다.

- `backend/`: Java 21, Spring Boot, MySQL/Flyway, JWT, JDBC batch, STOMP, 규칙 기반 분석
- `frontend/`: React, TypeScript, Vite, TanStack Query, Axios, STOMP, Vitest
- `scripts/`: 계약 검증, Mock Receiver, API E2E, 장시간 입력 관찰, 전체 검증
- `contracts/`, `fixtures/`, `docs/`: 구현의 단일 계약과 재현 가능한 합성 입력·설계 근거

현재 MVP 흐름은 회원가입·JWT 로그인, 기기 등록과 활성 보정 프로필, 측정 상태 전이, 멱등 원본 프레임 수신, 양발 실시간 족압, 품질 집계, 복구 가능한 비동기 규칙 분석, 결과·추천·필터 가능한 기록 조회까지 구현되어 있습니다. 하드웨어가 없는 현재 단계의 자동 보정은 `identity-v1` 기능 시험용 프로필이며 화면과 문서에 이 제한을 표시합니다.

## 계약 1.1 (2026-09-04)

`contracts/openapi.yaml` 1.1.0은 실기기 수신기(`smart-insole-ble-gateway`)와 펌웨어 연동을 위해 다음을 확정합니다. 세부 의미는 `docs/02_API_AND_REALTIME_CONTRACT.md`, 결정 근거는 `docs/09_DECISION_LOG.md` DEC-024..032를 참고하세요.

- Frame Batch `schemaVersion` 1.0/1.1: 1.1은 프레임별 `receivedAt`, `protocolVersion`, `dataMode`, `calibrated`, `imuAvailable`, IMU 벡터, `flags`, `batchId`를 선택 필드로 추가합니다.
- 수신기용 API: `GET /internal/v1/measurement-sessions/{id}`, `GET /internal/v1/measurement-sessions?status=MEASURING`, `POST .../receiver-status`, heartbeat의 `batteryMv`/`firmwareVersion`. 409 `SESSION_NOT_MEASURING`은 `details.disposition`(RETRY/DROP)과 `Retry-After`를 줍니다.
- 데이터 의미: ADC 0..4095(`devices.adcMax`, 세션 스냅샷), 센서 순서 `layout-s01s08-v1`(S01..S08 `label`), sequence 단조 u32, `sampleRateHz` 50/100, `sourceType` 기본 DEVICE(시뮬레이터만 SIMULATED 명시).
- 분석 `rule-v1.2.0`: 6종 패턴 코드, 유효 걸음 창 기준 `observationLevel`/`occurrenceRate`, `observationSummary`, 센서별 share. 이전 결과의 새 필드는 `null`입니다.
- STOMP: 토큰 만료 시 `ERROR` 프레임(`TOKEN_EXPIRED`) 후 연결 종료.
- Flyway V5(프레임 메타·ADC·수신기 컬럼), V6(레이아웃 seed), V7(관찰 필드)이 추가되었습니다. 레거시 행의 `adc_max`는 65535로 백필됩니다.

## 빠른 시작

필수 도구는 Java 21, Node.js 20.19 이상(또는 22.12 이상), Python 3.10 이상, Docker Compose입니다.

```powershell
Copy-Item .env.example .env
# .env의 모든 replace-with-* 값을 로컬 전용 값으로 변경합니다.
docker compose --env-file .env up -d
```

`local` 프로필은 저장소 루트의 `.env`를 자동으로 읽습니다. 백엔드는 별도의 환경변수 복사 없이 실행할 수 있습니다.

`.env`에 `SEED_ADMIN_EMAIL`과 `SEED_ADMIN_PASSWORD`(8자 이상)를 두면 `local` 프로필의 백엔드가 시작할 때 그 계정을 한 번 만듭니다. 이미 있으면 건너뛰고, 비밀번호는 로그에 남기지 않습니다. 이 프로젝트에는 권한 구분이 없으므로 시드 계정도 일반 사용자와 같은 권한을 가집니다. `prod` 프로필에서는 동작하지 않습니다.

```powershell
cd backend
.\gradlew.bat bootRun
```

다른 터미널에서 프론트엔드를 실행합니다.

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

기본 접속 주소는 프론트엔드 `http://localhost:5173`, 백엔드 `http://localhost:8080`, health endpoint `http://localhost:8080/actuator/health`입니다.

전체 정적 검증은 저장소 루트에서 실행합니다.

```powershell
python -m pip install -r scripts/requirements-contracts.txt
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-all.ps1
```

실행 중인 백엔드까지 포함한 API E2E는 Receiver Key 환경변수를 설정하고 `-E2E`를 추가합니다. MySQL·백엔드·BLE 수신기 CLI가 모두 있을 때는 `-GatewayE2E`로 하드웨어 없는 전 구간 mock E2E(`scripts/e2e_gateway_mock.py`)를 실행할 수 있으며 기본값은 SKIP입니다. 상세한 Mock Receiver(1.1 배치, 세션 sourceType 확인)와 장시간 관찰 사용법은 `scripts/README.md`를 참고하세요.

## 기본 기술 선택

| 구분 | 기본 선택 |
|---|---|
| 저장소 | 백엔드·프론트엔드 모노레포 권장 |
| 백엔드 | Java 21, Spring Boot, Gradle |
| DB | MySQL, Flyway |
| 일반 저장 | Spring Data JPA |
| 센서 원본 대량 저장 | Spring JDBC Batch |
| 인증 | Spring Security + JWT |
| 실시간 | WebSocket + STOMP |
| 프론트엔드 | React + TypeScript + Vite |
| 서버 상태 | TanStack Query |
| HTTP | Axios |
| 실시간 클라이언트 | `@stomp/stompjs` |
| 프론트 테스트 | Vitest + React Testing Library |
| 통합 테스트 | Testcontainers, 필요 시 Playwright |

기존 저장소가 이미 다른 버전이나 일관된 라이브러리를 사용한다면 기존 구조와 잠금 파일을 우선합니다. Codex가 임의로 전체 기술 스택을 바꾸면 안 됩니다.

## 저장소 구조

```text
smart-insole/
├── AGENTS.md
├── CODEX_MASTER_PROMPT.md
├── PLANS.md
├── contracts/
│   ├── openapi.yaml
│   └── realtime-message.schema.json
├── docs/
├── fixtures/
├── prompts/
├── scripts/
├── backend/
│   ├── build.gradle
│   └── src/
└── frontend/
    ├── package.json
    └── src/
```

프론트엔드와 백엔드를 모노레포에 두면 한 PR에서 계약·백엔드·프론트엔드를 함께 검증할 수 있습니다. 펌웨어와 BLE 수신기는 별도 저장소로 유지해도 됩니다.

## 별도 저장소를 이미 사용하는 경우

백엔드 저장소에는 루트 공통 규칙과 `backend/AGENTS.md`, 계약 파일, 백엔드 문서와 프롬프트를 복사합니다. 프론트엔드 저장소에는 루트 공통 규칙과 `frontend/AGENTS.md`, 같은 계약 파일, 프론트 문서와 프롬프트를 복사합니다.

계약 파일은 두 저장소에서 반드시 같은 버전을 사용해야 합니다.

## 문서 읽는 순서

1. `docs/00_PROJECT_BRIEF.md`
2. `docs/01_ARCHITECTURE.md`
3. `docs/02_API_AND_REALTIME_CONTRACT.md`
4. `docs/03_DATA_MODEL.md`
5. `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`
6. `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md`
7. `docs/06_INTEGRATION_AND_TEST_PLAN.md`
8. `docs/07_TASK_BOARD.md`
9. `docs/08_CODEX_OPERATION_GUIDE.md`
10. `docs/09_DECISION_LOG.md`
11. `docs/10_UI_SPEC.md`
12. `docs/11_HUMAN_REVIEW_CHECKLIST.md`

## 설계 문서로 변경 작업을 시작하는 방법

1. `AGENTS.md`와 변경 영역의 하위 `AGENTS.md`를 읽습니다.
2. `contracts/`와 관련 설계 문서를 확인합니다.
3. `docs/07_TASK_BOARD.md`에서 변경 범위를 고릅니다.
4. 단계별 프롬프트 또는 `CODEX_MASTER_PROMPT.md`를 참고합니다.
5. 변경 후 영역별 테스트와 루트 검증 스크립트를 실행합니다.

매번 프롬프트 파일을 고르기 어렵다면 `CODEX_MASTER_PROMPT.md`를 사용합니다. 이 프롬프트는 작업 보드에서 **의존성이 충족된 다음 작업 한 개만** 수행하게 합니다.

## 가장 중요한 운영 원칙

- 후속 변경은 작업 보드의 작은 단위와 명시된 의존성을 따라 진행합니다.
- API 변경은 `contracts/`를 먼저 바꾼 뒤 백엔드와 프론트엔드에 반영합니다.
- 테스트를 삭제하거나 약화해 통과시키지 않습니다.
- 실제 비밀번호, JWT Secret, Receiver API Key를 저장소에 넣지 않습니다.
- 의료 진단을 확정하는 문구를 추가하지 않습니다.
- Codex가 작성한 코드는 사람이 diff와 테스트 결과를 검토한 뒤 병합합니다.

## 전체 구현 단계

| 단계 | 내용 | 프롬프트 |
|---:|---|---|
| 1 | 저장소·로컬 실행 기반 | `01_REPOSITORY_BOOTSTRAP.md` |
| 2 | 계약·Fixture 기반 | `02_CONTRACT_AND_MOCK.md` |
| 3 | 백엔드 공통 기반 | `03_BACKEND_FOUNDATION.md` |
| 4 | 백엔드 인증 | `04_BACKEND_AUTH.md` |
| 5 | 기기·측정 세션 | `05_BACKEND_DEVICE_SESSION.md` |
| 6 | 센서 데이터 수신 | `06_BACKEND_INGESTION.md` |
| 7 | 실시간 처리 | `07_BACKEND_REALTIME.md` |
| 8 | 분석·결과 API | `08_BACKEND_ANALYSIS.md` |
| 9 | 프론트 기반·인증 | `09_FRONTEND_FOUNDATION.md` |
| 10 | 측정·실시간 화면 | `10_FRONTEND_MEASUREMENT.md` |
| 11 | 결과·운동·기록 | `11_FRONTEND_RESULTS_HISTORY.md` |
| 12 | 전체 연동·E2E | `12_INTEGRATION_E2E.md` |
| 13 | 최종 리뷰 | `13_FINAL_REVIEW.md` |

계약 확정 후에는 서로 다른 브랜치나 Git worktree에서 백엔드와 프론트엔드를 병렬로 진행할 수 있습니다.

## 최종 완료 기준

```text
로그인
→ 왼발·오른발 기기 선택
→ 측정 세션 생성·시작
→ Mock Receiver(또는 BLE 수신기)가 50/100Hz 원본 프레임을 schemaVersion 1.1 배치로 전송
→ 백엔드가 중복 없이 저장
→ 프론트엔드가 양발 히트맵 표시
→ 측정 종료
→ 규칙 기반 분석
→ 결과·운동 가이드 저장
→ 결과 페이지와 기록 페이지 조회
```

결과는 질환 진단이 아니라 **관찰된 족압·보행 패턴과 데이터 품질**을 설명해야 합니다.

## 운영·마이그레이션 원칙

- Flyway migration은 적용 후 수정하지 않고 새 버전 파일로 전진 적용합니다.
- 운영 DB migration 전에는 사용하는 인프라의 검증된 백업·복구 절차로 백업하고, 복구 연습이 끝난 백업만 신뢰합니다.
- 운영에서는 `prod` 프로필을 사용하고 DB 비밀번호, JWT Secret, Receiver Key, 허용 Origin을 환경변수 또는 비밀 저장소로 주입합니다.
- 원본 센서 배열과 인증 정보는 로그에 남기지 않습니다. Actuator는 health만 외부에 노출합니다.

## 담당자 검토

Codex 결과를 승인하기 전에는 `docs/11_HUMAN_REVIEW_CHECKLIST.md`를 사용해 작업 범위, 실제 테스트 결과, 계약 일치, 보안 위험을 확인합니다.

## 패키지 검증

- `VALIDATION_REPORT.md`: OpenAPI 참조, JSON Schema, fixture, 작업 ID 매핑 검증 결과
- `FILE_INDEX.md`: 전체 파일 색인

이 저장소의 계약과 설계 문서는 구현 코드와 함께 유지합니다. API 또는 실시간 메시지를 변경할 때는 `contracts/`를 먼저 수정하고 백엔드 DTO, 생성된 프론트 타입, fixture와 통합 테스트를 같은 변경에서 검증해야 합니다.
