# 7단계 — 실시간 Snapshot과 WebSocket

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

`BE-008`, `BE-009`

## 필수 추가 문서

- `backend/AGENTS.md`
- `contracts/realtime-message.schema.json`
- OpenAPI realtime snapshot schema
- `docs/02_API_AND_REALTIME_CONTRACT.md`
- `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`
- `docs/10_UI_SPEC.md`

## ID별 목표

### BE-008 — Snapshot·STOMP·권한

- WebSocket endpoint `/ws`와 세션 topic을 구성합니다.
- STOMP CONNECT에서 JWT를 검증하고 구독 시 세션 소유권을 확인합니다.
- 각 세션의 최신 left/right 데이터를 결합하는 snapshot store를 구현합니다.
- `GET /api/v1/measurement-sessions/{id}/realtime-snapshot`을 구현합니다.
- 세션 종료·취소 시 lifecycle 정리를 수행합니다.
- 메시지 발행 실패가 이미 성공한 원본 DB 저장을 rollback하지 않게 transaction 경계를 분리합니다.
- 애플리케이션 다중 인스턴스 지원은 범위 밖이며 현재 한계를 문서화합니다.

### BE-009 — 보정·CoP·접촉·발행 제한

- 측정 당시 고정한 calibration과 layout을 사용해 표시용 0~100 값을 계산합니다.
- total pressure, CoP, contact state, connected 상태, 품질을 계산합니다.
- 압력이 0이면 CoP는 `null`입니다.
- 왼발 또는 오른발만 있는 snapshot을 허용합니다.
- 한 발당 센서 6/8개를 지원합니다.
- 메시지는 `realtime-message.schema.json`과 일치해야 합니다.
- 원본 입력 100Hz를 그대로 모두 발행하지 말고 구성 가능한 10~20Hz로 제한합니다.
- snapshot은 최신 상태만 보관하며 무제한 history를 메모리에 누적하지 않습니다.

## 필수 테스트

- 인증 CONNECT와 미인증 거절
- 사용자 A 세션 topic을 B가 구독하지 못함
- left/right 도착 순서가 달라도 결합
- 한쪽 발 null
- 재접속 REST snapshot
- 6/8센서
- 정규화 상·하한
- CoP와 zero pressure
- 발행 제한
- publish 실패 후 DB 저장 유지
- JSON Schema 예시 검증

## 검증

```bash
cd backend
./gradlew test
./gradlew check
```
