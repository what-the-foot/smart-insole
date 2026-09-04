# 8단계 — 분석·결과·운영

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

`BE-010`, `BE-011`, `BE-012`, `BE-013`, `BE-014`, `BE-015`

## 필수 추가 문서

- `backend/AGENTS.md`
- `docs/03_DATA_MODEL.md`
- `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`
- `docs/06_INTEGRATION_AND_TEST_PLAN.md`
- OpenAPI의 result와 pagination
- 분석 관련 fixture

## ID별 목표

### BE-010 — AnalysisJob
- 측정 종료와 함께 중복 없이 PENDING job을 생성합니다.
- PENDING/RUNNING/COMPLETED/FAILED, algorithmVersion, attemptCount, 오류 요약을 DB에 둡니다.
- 서버 재시작 후 미완료 작업을 식별할 수 있게 합니다.
- 같은 세션·algorithmVersion의 중복 실행을 막습니다.

### BE-011 — 분석 파이프라인
- 품질 검사 → 보정 → 필터 → 접촉 이벤트 → 특징 → 규칙 → 추천 → 저장 순서로 분리합니다.
- 원본 프레임을 `deviceTimeMs`, `sequence`로 정렬하고 수정하지 않습니다.
- 데이터 부족·한쪽 발 부족을 명시적으로 처리합니다.
- 같은 입력과 버전은 같은 결과를 만드는 결정성을 테스트합니다.

### BE-012 — 특징값과 패턴 규칙
- cadence, 접촉 시간, symmetry, 영역별 비율, peak, CoP 요약을 계산합니다.
- 초기 패턴 코드를 구현합니다.
- 임계값을 버전 설정으로 분리하고 근거 미확정 값은 테스트용 초기값이라고 문서화합니다.
- 경계값, 0 나눗셈, POOR 품질을 테스트합니다.
- 질환 진단 코드나 확정 문구를 만들지 않습니다.

### BE-013 — 결과와 추천 API
- PROCESSING이면 202, COMPLETED면 계약의 결과를 200으로 반환합니다.
- quality, gait summary, distribution, patterns, evidence, recommendations, algorithmVersion, disclaimer를 포함합니다.
- 추천은 패턴-가이드 매핑이며 치료 효과를 보장하지 않습니다.

### BE-014 — 기록 pagination
- 인증 사용자의 세션 목록, 필터, size 상한, 상세·결과 조회를 구현합니다.
- N+1과 무제한 원본 조회를 막습니다.
- 정렬 기준과 페이지 응답을 OpenAPI와 일치시킵니다.

### BE-015 — 운영 설정
- 운영 profile, Dockerfile, 구조화 로그, request 크기, CORS, Actuator 공개 범위, migration·백업 문서를 정리합니다.
- 센서 배열, 토큰, 비밀번호, API Key를 일반 로그에 남기지 않습니다.
- 관측 가능 지표는 실제 구현된 범위만 문서화합니다.

## 필수 테스트

선택한 ID의 정상·실패·경계 테스트에 더해 다음 원칙을 적용하세요.

- MySQL이 필요한 동작은 Testcontainers
- 동일 입력 결정성
- algorithmVersion별 결과 분리
- PROCESSING 202 / COMPLETED 200
- POOR 품질 문구
- 다른 사용자 403
- pagination size 상한
- disclaimer 존재
- 분석 실패와 job 상태
- 서버 재시작 복구 정책 검증

## 검증

```bash
cd backend
./gradlew test
./gradlew check
```
