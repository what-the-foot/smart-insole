# 07. 작업 보드

## 규칙

- 한 Codex 실행에서 작업 ID 하나만 구현합니다.
- 의존성이 끝나지 않은 작업을 우회하지 않습니다.
- 기존 구현은 중복 생성하지 않고 검증합니다.
- 테스트 성공 후에만 체크합니다.
- 범위 밖 발견은 구현하지 않고 기록합니다.

## 공통

| 상태 | ID | 작업 | 의존성 |
|---|---|---|---|
| [ ] | INF-001 | 모노레포 구조·README | 없음 |
| [ ] | INF-002 | 로컬 MySQL Compose·env 예시 | INF-001 |
| [ ] | INF-003 | 루트 검증 스크립트 골격 | INF-001 |
| [ ] | CON-001 | OpenAPI·Realtime schema 검증 | INF-001 |
| [ ] | CON-002 | Fixture·계약 검증 | CON-001 |

## 백엔드

| 상태 | ID | 작업 | 의존성 |
|---|---|---|---|
| [ ] | BE-001 | Spring Boot 기반 | INF-001, INF-002 |
| [ ] | BE-002 | 오류·Validation·Clock·Actuator | BE-001 |
| [ ] | BE-003 | 사용자·가입·로그인·JWT | BE-002 |
| [ ] | BE-004 | Layout·Device·Calibration | BE-003, CON-001 |
| [ ] | BE-005 | MeasurementSession | BE-004 |
| [ ] | BE-006 | Receiver·Frame JDBC batch | BE-005, CON-002 |
| [ ] | BE-007 | 수신 품질·gap | BE-006 |
| [ ] | BE-008 | Snapshot·STOMP·권한 | BE-006 |
| [ ] | BE-009 | 보정·CoP·contact·발행 제한 | BE-008 |
| [ ] | BE-010 | AnalysisJob | BE-005 |
| [ ] | BE-011 | 분석 파이프라인 | BE-007, BE-010 |
| [ ] | BE-012 | 특징값·패턴 규칙 | BE-011 |
| [ ] | BE-013 | 결과·추천 API | BE-012 |
| [ ] | BE-014 | 기록 pagination | BE-013 |
| [ ] | BE-015 | 운영 설정·로그·Dockerfile | BE-014 |

## 프론트엔드

| 상태 | ID | 작업 | 의존성 |
|---|---|---|---|
| [ ] | FE-001 | React/TS/Vite 기반 | INF-001 |
| [ ] | FE-002 | OpenAPI 타입·Axios | FE-001, CON-001 |
| [ ] | FE-003 | 인증 UI·보호 라우트 | FE-002, BE-003 |
| [ ] | FE-004 | 앱 셸·대시보드 | FE-003 |
| [ ] | FE-005 | 기기·측정 준비 | FE-002, BE-004 |
| [ ] | FE-006 | 세션 제어 | FE-005, BE-005 |
| [ ] | FE-007 | STOMP·재연결 | FE-002, BE-008 |
| [ ] | FE-008 | 양발 실시간 화면 | FE-006, FE-007, BE-009 |
| [ ] | FE-009 | 분석 처리 중 | FE-006, BE-010 |
| [ ] | FE-010 | 결과 | FE-009, BE-013 |
| [ ] | FE-011 | 운동 가이드 | FE-010, BE-013 |
| [ ] | FE-012 | 기록 | FE-010, BE-014 |
| [ ] | FE-013 | 접근성·반응형·성능 | FE-008, FE-010, FE-012 |

## 통합

| 상태 | ID | 작업 | 의존성 |
|---|---|---|---|
| [ ] | INT-001 | Mock Receiver | BE-006, CON-002 |
| [ ] | INT-002 | 정상 E2E | FE-008, BE-013, INT-001 |
| [ ] | INT-003 | 중복·gap·out-of-order | BE-007, INT-001 |
| [ ] | INT-004 | 한쪽 발·재연결 | FE-008, BE-009 |
| [ ] | INT-005 | REST·WebSocket 소유권 | FE-003, BE-008 |
| [ ] | INT-006 | `verify-all.sh` 완성 | 핵심 전체 |
| [ ] | INT-007 | 장시간 관찰 | INT-002 |
| [ ] | INT-008 | 최종 리뷰 | INT-006 |

## 병렬 가능

계약 확정 후 서로 다른 worktree에서 진행:

```text
Backend worktree: BE-*
Frontend worktree: FE-001, FE-002 및 Mock 기반 UI
```

같은 계약 파일을 여러 에이전트가 동시에 수정하지 않습니다.

## 브랜치·커밋 예

```text
feat/be-006-frame-ingestion
feat/fe-008-live-measurement

feat(backend): implement idempotent frame ingestion
feat(frontend): add bilateral live pressure view
test(backend): cover duplicate frame delivery
docs(contract): update realtime schema
```

요청받지 않으면 Codex는 commit하지 않습니다.

## 진행 기록

- 아직 없음

## 발견 사항

- 원본 장기 보존 기간은 MVP 이후 결정
- Refresh Token은 운영 인증 고도화에서 결정
- 머신러닝 분리는 규칙 엔진 검증 이후 결정
