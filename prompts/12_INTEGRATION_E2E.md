# 12단계 — Mock Receiver와 전체 통합

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

`INT-001`, `INT-002`, `INT-003`, `INT-004`, `INT-005`, `INT-006`, `INT-007`

## 필수 추가 문서

- `docs/06_INTEGRATION_AND_TEST_PLAN.md`
- `docs/07_TASK_BOARD.md`
- `fixtures/`
- `contracts/`
- backend와 frontend의 AGENTS.md

## ID별 목표

### INT-001 — Mock Receiver
- fixture를 읽어 실제 Receiver API에 100~200ms batch로 전송하는 도구를 구현합니다.
- session ID, base URL, Receiver Key는 CLI 또는 환경변수로 받습니다.
- 실패 batch를 잃지 않고 제한된 재시도 또는 명확한 실패 보고를 합니다.
- server의 `lastSequenceByDevice`를 표시합니다.
- 실제 BLE 구현으로 위장하지 않고 SIMULATED임을 표시합니다.

### INT-002 — 정상 E2E
다음을 자동 또는 재현 가능한 절차로 검증합니다.

```text
가입/로그인 → 기기 등록 → 세션 생성·시작
→ 정상 fixture 전송 → 양발 realtime
→ 종료 → PROCESSING → COMPLETED
→ 결과 → 기록
```

### INT-003 — 중복·gap·out-of-order
- 동일 batch 재전송 시 DB 행 불변과 duplicate 집계
- sequence gap quality flag
- out-of-order 도착 후 실제 시간 정렬
- 의도한 fixture별 기대를 검증합니다.

### INT-004 — 한쪽 발·재연결
- 오른발 중단 시 왼발 UI 유지와 품질 경고
- WebSocket 종료 후 snapshot 복구와 재구독
- 중복 구독이 없는지 검증합니다.

### INT-005 — REST·WebSocket 소유권
- 사용자 A 세션을 사용자 B가 REST 조회하지 못함
- B가 A의 topic을 구독하지 못함
- Receiver 인증과 사용자 인증의 경계를 검증합니다.

### INT-006 — `verify-all.sh`
- 계약 → backend → frontend → 선택 E2E 순으로 전체 검증합니다.
- 누락된 구성 요소를 조용히 성공으로 처리하지 않습니다.
- 하위 실패를 최종 exit code로 전달합니다.
- CI와 로컬에서 같은 핵심 명령을 사용합니다.

### INT-007 — 장시간 관찰
- 양발 약 200 frame/s 입력을 batch로 일정 시간 재현합니다.
- DB batch 시간, WebSocket 10~20Hz, 브라우저 렌더·메모리, 분석 메모리를 관찰합니다.
- 임의 SLA를 선언하지 말고 측정 환경·결과·병목을 기록합니다.
- 실제 환자 데이터는 사용하지 않습니다.

## 병렬 실행 주의

review·로그 분석처럼 읽기 중심 작업은 병렬화할 수 있습니다. 같은 계약이나 동일 소스 파일을 여러 에이전트가 동시에 수정하게 하지 마세요.

## 검증

```bash
./scripts/verify-all.sh
```

실행 환경에서 Docker 또는 브라우저 E2E가 불가능하면 이유와 정확한 미검증 범위를 보고하세요.
