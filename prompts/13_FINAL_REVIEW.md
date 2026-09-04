# 13단계 — 최종 종합 리뷰

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

`INT-008`

## 작업 성격

기본적으로 **리뷰 우선**입니다. 먼저 코드를 수정하지 말고 전체 위험을 조사하세요. 발견된 문제를 한꺼번에 대규모로 고치지 말고, 심각도가 높은 범위 내 문제만 사용자의 명시적 범위에 따라 수정합니다.

## 리뷰 영역

1. 계약
   - OpenAPI와 backend DTO/응답
   - OpenAPI 생성 타입과 frontend 사용
   - 실시간 JSON Schema와 server/client
   - enum, nullable, 날짜, 단위, 6/8센서

2. 보안·소유권
   - JWT, Receiver Key, secret
   - 사용자 A/B의 REST·topic 격리
   - 로그 개인정보
   - CORS와 Actuator

3. 데이터 무결성·성능
   - frame 멱등성
   - JDBC batch
   - sequence/deviceTime 정렬
   - 원본 불변
   - transaction 경계
   - WebSocket 발행 실패
   - 메모리 누적

4. 분석·의료 표현
   - algorithmVersion
   - quality POOR
   - 결정성
   - 근거 미확정 threshold 표시
   - 진단·치료 보장 문구 여부

5. 프론트 품질
   - 재연결·cleanup
   - loading/empty/error
   - 양발·한쪽 발
   - 모바일·키보드·색상 외 상태
   - polling 종료

6. 테스트·운영
   - 정상·경계·실패
   - Testcontainers
   - E2E
   - migration
   - Docker build
   - `verify-all.sh`

## 선택적 subagent 리뷰 지시

도구가 지원하고 작업이 읽기 중심이라면 다음처럼 분리할 수 있습니다.

- Agent A: 인증·소유권·secret
- Agent B: DB·멱등성·transaction·성능
- Agent C: 계약·WebSocket lifecycle
- Agent D: 프론트 접근성·테스트·의료 표현

모든 리뷰가 끝날 때까지 기다린 뒤 중복을 제거하고 파일·줄 위치와 심각도별로 통합하세요. 동일 파일을 병렬 수정하지 마세요.

## 최종 검증

```bash
./scripts/verify-all.sh
git diff --check
git status --short
```

## 출력 형식

### Blocking
병합 전 반드시 해결할 문제

### High
데이터·보안·핵심 기능에 큰 영향

### Medium
회귀 가능성 또는 유지보수 위험

### Low
개선 제안

각 항목에:
- 위치
- 재현 또는 근거
- 영향
- 최소 수정안
- 관련 작업 ID

문제가 없다고 판단한 영역도 어떤 명령과 테스트로 확인했는지 적으세요. 실행하지 않은 검증을 통과로 간주하지 마세요.
