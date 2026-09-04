# 2단계 — 공통 계약과 Fixture

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

`CON-001`, `CON-002`

## 필수 추가 문서

- `contracts/openapi.yaml`
- `contracts/realtime-message.schema.json`
- `docs/06_INTEGRATION_AND_TEST_PLAN.md`
- `fixtures/README.md`

## ID별 목표

### CON-001 — OpenAPI와 실시간 Schema 검증

- OpenAPI 문법, 내부 `$ref`, 중복 `operationId`, enum, 필수 필드를 검사하는 재현 가능한 스크립트를 구현합니다.
- 실시간 JSON Schema의 문법과 예시 메시지를 검증합니다.
- REST와 실시간에서 공통으로 쓰는 `FootSide`, 상태, 품질 값의 불일치를 찾습니다.
- 계약 변경이 필요하면 먼저 영향 범위를 보고하고 계약·문서·예시를 함께 수정합니다.
- backend 또는 frontend 구현 코드는 이 작업에서 만들지 않습니다.

완료 조건:
- 한 명령으로 계약 검증 가능
- 깨진 `$ref`나 잘못된 예시에서 실패
- 정상 계약에서 성공
- 실행 방법이 문서에 있음

### CON-002 — Fixture와 계약 검증

- `fixtures/`에 기능 테스트용 프레임 배치를 정의합니다.
- 최소한 정상, 왼발 상대 증가, 중복, sequence gap, sensor stuck, 오른발 중단, out-of-order 시나리오를 준비하거나 생성 규칙을 구현합니다.
- fixture는 임상 데이터가 아니라 기능 검증용이라는 설명을 유지합니다.
- 각 fixture의 입력 목적과 기대 결과를 기계 판독 가능한 manifest 또는 README로 기록합니다.
- OpenAPI의 `FrameBatchRequest` 구조와 센서 수 6/8 규칙을 검증합니다.
- 실제 Receiver Key, 사용자 개인정보, 실제 환자 데이터는 넣지 않습니다.

완료 조건:
- 모든 JSON parse 성공
- 각 fixture가 계약과 일치
- 중복·gap 등 의도된 예외가 문서에 명시됨
- deterministic한 생성 방식

## 검증 예

```bash
./scripts/validate-contracts.sh
git diff --check
```

Node 또는 Python 도구를 추가한다면 버전과 설치 방법을 고정하고 불필요한 대형 런타임 의존성을 만들지 마세요.
