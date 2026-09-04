# 9단계 — 프론트엔드 기반·계약·인증

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

`FE-001`, `FE-002`, `FE-003`, `FE-004`

## 필수 추가 문서

- `frontend/AGENTS.md`
- `docs/05_FRONTEND_IMPLEMENTATION_PLAN.md`
- `docs/10_UI_SPEC.md`
- `contracts/openapi.yaml`

## ID별 목표

### FE-001 — React/TypeScript/Vite 기반
- 기존 frontend가 없을 때만 React + TypeScript + Vite를 생성합니다.
- 기존 잠금 파일이 있으면 package manager를 바꾸지 않습니다.
- Router, TanStack Query, Axios, Vitest, React Testing Library, ESLint 기반을 구성합니다.
- app shell, error boundary, test setup, 환경변수 타입을 만듭니다.
- 화면에 하드코딩한 API 응답을 기능 구현으로 간주하지 않습니다.

### FE-002 — OpenAPI 타입과 Axios
- `contracts/openapi.yaml`에서 타입을 생성하는 스크립트를 구성합니다.
- 생성 파일은 별도 디렉터리에 두고 직접 수정하지 않습니다.
- base URL, 인증 header, 공통 ApiError 변환, 날짜 처리를 중앙화합니다.
- auth/device/measurement API 함수의 최소 기반을 만듭니다.
- 계약이 생성 도구와 맞지 않으면 임의 타입으로 우회하지 말고 계약 문제를 보고합니다.

### FE-003 — 인증 UI와 보호 라우트
- 회원가입, 로그인, 로그아웃, 인증 상태 복구, 보호 라우트를 구현합니다.
- 401과 만료 시 무한 재시도를 막고 재로그인 안내를 제공합니다.
- 토큰을 로그·오류 화면에 노출하지 않습니다.
- 토큰 저장 방식과 XSS 위험을 문서화합니다. 서버 계약에 없는 Refresh Token은 만들지 않습니다.
- 입력 label, 오류 메시지, 키보드 조작을 지원합니다.

### FE-004 — 앱 셸과 대시보드
- header/navigation, 새 측정 CTA, 최근 측정, 기기 요약, loading/empty/error를 구현합니다.
- backend가 준비되지 않은 구간은 MSW 등 명시적 Mock으로 개발하되 실제 API 모드와 구분합니다.
- 모바일에서도 핵심 CTA가 보이게 합니다.

## 필수 테스트

- 타입 생성 재현
- API 오류 변환
- 로그인 성공·실패
- 미인증 보호 라우트
- 로그아웃·401 처리
- loading/empty/error
- 키보드와 label
- Mock이 production build에 섞이지 않는지

## 검증

실제 package manager에 맞게 실행하세요.

```bash
cd frontend
npm run lint
npm run test -- --run
npm run build
```
