# 10단계 — 프론트 측정·실시간 화면

아래 내용을 Codex의 새 작업에 그대로 전달하세요.

---

당신은 스마트 인솔 재활 보조 서비스 저장소의 구현 에이전트입니다. 목표는 백엔드와 프론트엔드가 공통 계약을 따르는 작동 가능한 MVP를 만드는 것입니다.

## 작업 전 필수 절차

1. 저장소 루트 `AGENTS.md`와 수정할 경로에서 가장 가까운 하위 `AGENTS.md`를 읽으세요.
2. `docs/00_PROJECT_BRIEF.md`, `docs/01_ARCHITECTURE.md`, `docs/02_API_AND_REALTIME_CONTRACT.md`, `docs/07_TASK_BOARD.md`, `docs/09_DECISION_LOG.md`를 읽으세요.
3. 현재 단계와 관련된 구현 계획서, `contracts/`, 기존 코드와 테스트를 확인하세요.
4. `git status`, 현재 브랜치, 디렉터리 구조, build/package 파일, 잠금 파일을 확인하세요.
5. 사용자가 이미 제공한 문서에 답이 있으면 다시 질문하지 말고 문서의 기본 결정을 따르세요.

## 작업 선택 규칙

- 아래 허용 작업 ID 중 **의존성이 충족된 첫 번째 미완료 ID 하나만** 선택하세요.
- 사용자가 특정 ID를 지정했다면 그 ID만 수행하세요.
- 이미 구현되어 있다면 중복 생성하지 말고 계약·테스트·완료 조건을 검증하세요.
- 선행 작업이 끝나지 않았다면 우회 구현을 만들지 말고 필요한 선행 ID를 보고한 뒤 코드 수정 없이 종료하세요.
- 한 번의 실행에서 여러 작업 ID를 동시에 완료 처리하지 마세요.

## 수정 전 출력

코드 수정 전에 다음을 짧게 보고하세요.

1. 선택한 작업 ID와 목표
2. 현재 구현 상태
3. 예상 수정 파일
4. 계약 또는 DB migration 영향
5. 실행할 테스트·검증 명령

## 공통 금지사항

- 관련 없는 리팩터링, 디렉터리 전면 재구성, 기술 스택 교체
- 계약을 무시한 DTO 또는 임의 필드 생성
- 테스트 삭제·비활성화·검증 약화
- secret, 실제 API Key, 토큰, 비밀번호 커밋 또는 로그 출력
- 타인의 기기·세션·결과·WebSocket topic 접근 허용
- 원본 ADC 값을 보정값으로 덮어쓰기
- 100Hz 원본 프레임을 JPA `save()` 반복으로 저장
- 질환을 확정하거나 치료 효과를 보장하는 사용자 문구
- 요청받지 않은 commit, push, merge, 브랜치 삭제

## 완료 보고 형식

1. 작업 ID
2. 구현 내용
3. 주요 변경 파일
4. 실행한 명령과 실제 결과
5. 계약·DB migration 변경 여부
6. 남은 위험 또는 실행하지 못한 검증
7. 다음으로 가능한 작업 ID

완료 조건과 테스트가 실제로 확인된 경우에만 `docs/07_TASK_BOARD.md`의 해당 ID를 체크하세요.

## 허용 작업 ID

`FE-005`, `FE-006`, `FE-007`, `FE-008`, `FE-009`

## 필수 추가 문서

- `frontend/AGENTS.md`
- `docs/02_API_AND_REALTIME_CONTRACT.md`
- `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md`
- `docs/10_UI_SPEC.md`
- OpenAPI와 실시간 JSON Schema

## ID별 목표

### FE-005 — 기기·측정 준비
- 내 기기 목록을 LEFT/RIGHT로 구분하고 센서 수, layout, 상태, 보정 안내를 보여줍니다.
- 양발 기기 선택 form에서 같은 기기·잘못된 방향을 막습니다.
- loading, empty, API error와 다음 행동을 제공합니다.

### FE-006 — 세션 제어
- 세션 생성·시작·종료·취소를 상태 기반 UI로 구현합니다.
- 중복 클릭과 중복 요청을 막습니다.
- 서버 상태를 단일 기준으로 사용하며 클라이언트가 임의로 완료 상태를 만들지 않습니다.
- 새로고침 후 세션 상세로 복구할 수 있게 합니다.

### FE-007 — STOMP 연결과 재연결
- IDLE/CONNECTING/CONNECTED/RECONNECTING/DISCONNECTED/ERROR 상태를 명확히 관리합니다.
- STOMP CONNECT에 JWT를 전달합니다.
- session 변경과 unmount에서 구독을 해제하고 중복 구독을 막습니다.
- 제한된 backoff 재연결 후 REST snapshot을 읽고 topic을 다시 구독합니다.
- schema가 잘못된 메시지는 화면 crash 없이 오류 상태로 처리합니다.

### FE-008 — 양발 실시간 측정
- 양발 각각의 센서 heatmap, total pressure, CoP, contact, connected 상태를 표시합니다.
- sensor layout 좌표와 0~100 상대값을 사용합니다.
- 6/8센서를 모두 지원합니다.
- LEFT/RIGHT를 시각·텍스트로 분명하게 표시합니다.
- 한쪽 발 데이터가 없거나 끊겨도 다른 발 화면을 유지합니다.
- 품질은 색상뿐 아니라 텍스트·아이콘·flag로 설명합니다.
- 메시지를 100Hz history로 무제한 누적하거나 전체 앱을 매번 rerender하지 않습니다.
- 모바일에서 종료·취소가 안전하게 조작되어야 합니다.

### FE-009 — 분석 처리 중
- 종료 후 PROCESSING 화면을 보여주고 결과 API를 제한된 간격으로 폴링합니다.
- COMPLETED/FAILED/취소/unmount에서 폴링을 중단합니다.
- 새로고침 후 복구합니다.
- 장시간 처리와 실패 시 사용자에게 다음 행동을 안내합니다.

## 필수 테스트

- 기기 방향과 중복 선택
- 상태별 허용 버튼과 중복 클릭
- STOMP 중복 구독·cleanup
- 재연결 snapshot 복구
- malformed message
- 양발·한쪽 발·6/8센서
- 품질 POOR
- heatmap 값 0/100 경계
- PROCESSING polling 종료 조건
- 모바일 핵심 조작의 DOM 접근성

## 검증

```bash
cd frontend
npm run lint
npm run test -- --run
npm run build
```
