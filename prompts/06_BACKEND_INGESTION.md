# 6단계 — Receiver와 센서 원본 수신

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

`BE-006`, `BE-007`

## 필수 추가 문서

- `backend/AGENTS.md`
- `docs/02_API_AND_REALTIME_CONTRACT.md`
- `docs/03_DATA_MODEL.md`
- `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`
- `docs/06_INTEGRATION_AND_TEST_PLAN.md`
- frame batch OpenAPI와 `fixtures/`

## ID별 목표

### BE-006 — Receiver 인증·Frame JDBC Batch

- `/internal/v1/measurement-sessions/{id}/frame-batches`를 구현합니다.
- 사용자 JWT와 분리된 Receiver API Key 인증을 적용합니다.
- 세션 `MEASURING`, 기기 배정, footSide, 센서 수, ADC 범위, sequence, deviceTime, schemaVersion을 검증합니다.
- 요청 전체 실패와 개별 프레임 부분 거절의 경계를 계약대로 구현합니다.
- 최대 200프레임 기본 상한을 적용합니다.
- 원본 프레임은 JDBC batch로 저장합니다.
- `(session_id, device_id, sequence)` DB unique 제약과 코드 처리를 함께 사용합니다.
- 동일 배치 재전송은 서버 오류가 아니라 accepted/duplicate 집계로 응답합니다.
- 원본값은 수정하지 않습니다.
- `lastSequenceByDevice`를 반환합니다.

### BE-007 — 수신 품질과 sequence gap

- 수신률, gap, duplicate, rejection, out-of-order, time jump, stuck sensor, 한쪽 발 미수신을 추적할 기반을 구현합니다.
- 품질은 `score`, `GOOD|ACCEPTABLE|POOR`, `flags[]`로 반환할 수 있어야 합니다.
- 동시 배치에서도 카운터 유실이나 last sequence 역행이 없게 합니다.
- 늦게 도착한 유효 프레임과 진짜 중복을 구분합니다.
- 품질 계산용 통계가 원본 저장 transaction을 불필요하게 실패시키지 않게 경계를 설계합니다.

## 필수 테스트

- 정상 양발 batch
- 동일 batch 전체 재전송
- 일부 중복
- 6/8센서
- 잘못된 API Key
- 잘못된 schema
- 세션 상태 오류
- 타 세션 기기와 footSide 불일치
- 일부 프레임 부분 거절
- 배치 상한
- gap, out-of-order, sequence 동시성
- MySQL unique와 JDBC batch

## 성능 확인

양발 100Hz를 100~200ms 단위로 묶은 fixture를 사용해 DB 호출이 프레임별 `save()` 반복이 아닌지 확인하세요. 실측하지 않은 처리량을 보장한다고 문서화하지 마세요.

## 검증

```bash
cd backend
./gradlew test
./gradlew check
```
