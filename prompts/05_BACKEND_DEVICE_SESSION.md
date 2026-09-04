# 5단계 — 기기·보정·측정 세션

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

`BE-004`, `BE-005`

## 필수 추가 문서

- `backend/AGENTS.md`
- `docs/03_DATA_MODEL.md`
- `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`
- OpenAPI의 Device, SensorLayout, Measurement 경로

## ID별 목표

### BE-004 — SensorLayout·Device·Calibration

- Flyway migration과 도메인·Repository·Service·Controller를 구현합니다.
- `layout-v1` 센서 좌표 seed를 제공합니다.
- 센서 수 6개 또는 8개를 허용하고 layout과 일치시킵니다.
- 내 기기 등록·목록·layout 조회를 구현합니다.
- serialNumber 중복, LEFT/RIGHT, 상태, 소유권을 검증합니다.
- 보정 프로필은 덮어쓰지 않고 버전별로 관리하며 활성 프로필을 식별합니다.
- 외부 응답에 다른 사용자의 정보가 섞이지 않게 합니다.

### BE-005 — MeasurementSession

- 세션 생성·시작·종료·취소·상세를 구현합니다.
- 세션 생성 시 왼발·오른발 기기가 현재 사용자 소유인지, 방향이 맞는지, 서로 다른 기기인지 검증합니다.
- 측정 당시 활성 calibration ID와 sensorLayoutVersion을 세션에 고정합니다.
- 상태 전이는 도메인 메서드로 제한합니다.
- 종료 요청은 `MEASURING → PROCESSING`이며 분석 작업 연결은 후속 작업에서 구현할 수 있습니다.
- 동일 상태 명령의 멱등성 정책은 계약·문서와 일치시킵니다.

허용 전이:

```text
CREATED → MEASURING | CANCELLED
MEASURING → PROCESSING | CANCELLED | FAILED
PROCESSING → COMPLETED | FAILED
```

## 필수 테스트

- 6/8센서 layout
- 기기 중복·방향·소유권
- 같은 기기를 양발로 선택하는 오류
- 세션의 calibration snapshot
- 모든 허용 상태 전이
- 금지 상태 전이 409
- 타인 세션 403
- 존재하지 않는 자원 404
- 동시 start/complete 시 상태 무결성

## 검증

```bash
cd backend
./gradlew test
./gradlew check
```
