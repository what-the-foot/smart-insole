# 파일 색인

구현과 검증에 필요한 진입점을 빠르게 찾기 위한 색인입니다. 생성물(`backend/build`, `frontend/dist`, `node_modules`)과 로컬 도구(`.tooling`)는 제외합니다.

## 루트

- `README.md`: 실행, 검증, 운영 원칙
- `.env.example`: 로컬 MySQL·JWT·Receiver·CORS 예시
- `docker-compose.yml`: MySQL 8.4 개발 서비스
- `AGENTS.md`, `CODEX_MASTER_PROMPT.md`, `PLANS.md`: 작업 규칙과 계획
- `VALIDATION_REPORT.md`: 최종 검증 결과와 환경 제한

## 계약과 합성 입력

- `contracts/openapi.yaml`: REST API 단일 계약 (1.1.0: Frame Batch 1.1, 수신기 세션 조회·receiver-status, rule-v1.2.0 결과)
- `contracts/realtime-message.schema.json`: STOMP payload 계약
- `fixtures/manifest.json`: fixture 목적과 파일 매핑
- `fixtures/frame-batch-*.json`: 정상, 6센서, 중복, gap, 역순, stuck, 비대칭, 한쪽 단절 입력(1.0)과 실기기 수신기 형태의 1.1 배치(`frame-batch-device-v1_1.json`)
- `fixtures/realtime-*.json`: 정상 양발·한쪽 단절 실시간 예시

## 백엔드

- `backend/build.gradle`, `backend/settings.gradle`, `backend/gradlew*`: Java 21/Spring Boot 빌드
- `backend/Dockerfile`: 운영용 컨테이너 빌드
- `backend/src/main/resources/application*.yml`: local/test/prod 설정
- `backend/src/main/resources/db/migration/`: V1 schema, V2 seed, V3 품질·index, V4 valid_step_count, V5 1.1 메타·adc_max·receiver, V6 layout-s01s08-v1, V7 관찰 필드
- `backend/src/main/java/com/smartinsole/auth/`: 회원가입, 로그인, BCrypt/JWT
- `backend/src/main/java/com/smartinsole/device/`: 센서 layout, 기기, 기능 시험용 활성 보정
- `backend/src/main/java/com/smartinsole/measurement/`: 세션 상태, 기록, JDBC batch 수신(1.1), 수신기 세션 조회·receiver-status, 품질 통계(O(1) gap, wrap·sample-rate 휴리스틱)
- `backend/src/main/java/com/smartinsole/realtime/`: snapshot, CoP, 10Hz STOMP, 연결·구독 권한
- `backend/src/main/java/com/smartinsole/analysis/`: durable job, 재시도·재시작 복구, rule-v1.2.0 규칙 분석(PatternCatalog, 관찰 단계), 결과
- `backend/src/main/java/com/smartinsole/recommendation/`: 추천 상세 조회와 결과 연결
- `backend/src/test/`: 도메인·통합·보안·동시성·MySQL Testcontainers 검증, `FrameBatchContractTest`(1.1 fixture 왕복), `support/` 테스트 헬퍼

## 프런트엔드

- `frontend/package.json`, `frontend/package-lock.json`: 고정된 npm 빌드와 검증 명령
- `frontend/src/api/`: Axios, TanStack Query, 생성 타입, 응답 runtime 검증
- `frontend/src/app/`, `frontend/src/features/auth/`: 앱 셸, 오류 경계, 인증 상태와 보호 라우트
- `frontend/src/pages/`: 인증, 대시보드, 기기, 측정, 결과, 추천, 기록 화면
- `frontend/src/features/realtime/`: 엄격한 메시지 parser, STOMP 재연결, 양발 heatmap
- `frontend/src/features/results/`: 분석 polling과 비진단 결과 표현
- `frontend/src/**/*.test.*`: 인증, 계약 parser, 캐시 격리, 측정 재시도, 결과·기록·추천 회귀 테스트
- `frontend/src/styles/global.css`: 반응형·접근성 포함 전역 UI

## 실행·검증 도구

- `scripts/validate_contracts.py`: OpenAPI, JSON Schema, fixture 의미·보안 검증
- `scripts/verify-all.{sh,ps1}`: 계약 → backend → frontend → 선택 E2E → 선택 gateway mock E2E(`--gateway-e2e`/`-GatewayE2E`)
- `scripts/mock-receiver/`: 세션 sourceType·sampleRateHz를 확인하는 schemaVersion 1.1 합성 Receiver
- `scripts/e2e_smoke.py`: 가입부터 결과·추천·기록까지 API smoke
- `scripts/e2e_gateway_mock.py`: MySQL·백엔드·BLE 수신기 CLI로 실행하는 하드웨어 없는 전 구간 E2E
- `scripts/observe_long_run.py`: 양발 장시간 batch 수신 관찰
- `scripts/README.md`: 각 도구의 옵션과 사용법

## 설계 문서

- `docs/00_PROJECT_BRIEF.md`: 제품 범위와 비진단 원칙
- `docs/01_ARCHITECTURE.md`: 구성요소와 데이터 흐름
- `docs/02_API_AND_REALTIME_CONTRACT.md`: REST/STOMP 의미 계약
- `docs/03_DATA_MODEL.md`: 모델, 제약, 센서 좌표 정책
- `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`: 백엔드 완료 기준
- `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md`: 프런트 완료 기준
- `docs/06_INTEGRATION_AND_TEST_PLAN.md`: 통합 시나리오
- `docs/07_TASK_BOARD.md`: 구현 상태와 남은 환경 검증
- `docs/08_CODEX_OPERATION_GUIDE.md`: 변경 운영 방식
- `docs/09_DECISION_LOG.md`: 설계 결정 기록
- `docs/10_UI_SPEC.md`: 화면·상태·문구 명세
- `docs/11_HUMAN_REVIEW_CHECKLIST.md`: 승인 전 사람 검토 항목
