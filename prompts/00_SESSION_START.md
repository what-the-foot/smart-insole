# Codex 첫 세션 — 저장소 진단 전용

아래 내용을 Codex의 첫 메시지로 전달하세요.

---

당신은 스마트 인솔 재활 보조 서비스 저장소를 인수받은 구현 에이전트입니다. 이 작업은 **읽기 전용 진단**입니다. 코드를 수정하거나 파일을 생성·삭제하지 마세요.

## 읽을 항목

1. 루트 `AGENTS.md`
2. `backend/AGENTS.md`
3. `frontend/AGENTS.md`
4. `README.md`
5. `docs/00_PROJECT_BRIEF.md`
6. `docs/01_ARCHITECTURE.md`
7. `docs/02_API_AND_REALTIME_CONTRACT.md`
8. `docs/03_DATA_MODEL.md`
9. `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`
10. `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md`
11. `docs/06_INTEGRATION_AND_TEST_PLAN.md`
12. `docs/07_TASK_BOARD.md`
13. `docs/09_DECISION_LOG.md`
14. `docs/10_UI_SPEC.md`
15. `contracts/openapi.yaml`
16. `contracts/realtime-message.schema.json`

## 조사할 내용

- 현재 저장소 구조와 실제 구현 기술
- backend와 frontend가 이미 존재하는지
- build 파일, package manager, 잠금 파일, Java/Node 버전
- 실행·test·lint·build 스크립트
- DB migration과 환경변수 예시
- 구현된 REST API와 OpenAPI 차이
- WebSocket 메시지와 JSON Schema 차이
- 완료된 것으로 보이는 작업 ID와 근거
- 실패하거나 실행할 수 없는 검증
- secret 또는 개인정보 노출 위험
- 문서와 코드가 충돌하는 부분

가능한 범위에서 읽기 전용 명령으로 현재 테스트 상태를 확인할 수 있습니다. 테스트가 파일을 수정하거나 외부 서비스를 변경하지 않는지 먼저 확인하세요.

## 출력 형식

### 1. 저장소 현황
backend, frontend, contracts, tests, scripts의 상태를 표로 요약하세요.

### 2. 문서 대비 차이
파일 위치와 구체적인 차이를 작성하세요.

### 3. 작업 보드 판정
각 완료 추정 ID, 미완료 ID, 판단 근거를 작성하세요. 작업 보드는 수정하지 마세요.

### 4. 위험
보안, 데이터 무결성, 계약, 테스트, 성능 위험을 심각도순으로 작성하세요.

### 5. 다음 작업
의존성이 충족된 다음 작업 ID 하나와 예상 수정 파일·검증 명령을 제안하세요.

추측으로 완료 처리하지 말고, 확인하지 못한 내용은 “미확인”이라고 표시하세요.
