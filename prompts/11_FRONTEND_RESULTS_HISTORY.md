# 11단계 — 결과·운동 가이드·기록

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

`FE-010`, `FE-011`, `FE-012`, `FE-013`

## 필수 추가 문서

- `frontend/AGENTS.md`
- `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md`
- `docs/10_UI_SPEC.md`
- OpenAPI의 AnalysisResult와 pagination

## ID별 목표

### FE-010 — 분석 결과
- 데이터 품질, 주요 gait 지표, 좌우·영역 분포, 패턴, evidence, algorithmVersion을 표시합니다.
- 값의 단위와 계산 의미를 사용자 친화적으로 보여줍니다.
- 패턴이 없음을 “질환 없음” 또는 “완전 정상”으로 확정하지 않습니다.
- 서버 disclaimer를 항상 보이게 합니다.
- PROCESSING, FAILED, 없음, 권한 오류를 구분합니다.

### FE-011 — 운동 가이드
- 운동 제목, 목적, 수행 단계, 시간·횟수, 주의사항을 표시합니다.
- 통증이 생기면 중단하고 전문가 평가가 필요할 수 있음을 안내합니다.
- 치료·교정 효과를 보장하지 않습니다.
- 운동 미디어가 없으면 깨진 링크 대신 명확한 placeholder를 사용합니다.

### FE-012 — 기록
- 서버 pagination 기반 목록과 상태·날짜 필터를 구현합니다.
- quality와 주요 패턴을 요약하고 상세 결과로 이동합니다.
- 필터 변경 시 page 정책을 일관되게 처리합니다.
- loading, empty, error, 재시도를 제공합니다.
- 전체 원본 프레임을 브라우저로 내려받지 않습니다.

### FE-013 — 접근성·반응형·성능
- 모바일·태블릿·데스크톱 핵심 흐름을 점검합니다.
- 키보드, focus, form label, heading, 색상 외 상태 표현을 보완합니다.
- 느린 네트워크와 오류 상태를 테스트합니다.
- WebSocket cleanup, polling, 렌더 빈도, 메모리 누수를 점검합니다.
- 관련 없는 디자인 전면 교체는 하지 않습니다.

## 필수 테스트

- GOOD/ACCEPTABLE/POOR 결과
- 패턴 0개와 여러 개
- 단위·disclaimer
- 운동 안전 문구
- pagination과 필터
- loading/empty/error
- 키보드 focus와 label
- 좁은 viewport
- 불필요한 재렌더 또는 구독 누수 회귀

## 검증

```bash
cd frontend
npm run lint
npm run test -- --run
npm run build
```
