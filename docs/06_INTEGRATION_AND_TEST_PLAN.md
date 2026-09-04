# 06. 통합·테스트 계획

## 막아야 할 위험

- 원본 중복 저장
- LEFT/RIGHT 뒤바뀜
- WebSocket 실패가 DB 저장을 깨뜨림
- 낮은 품질을 정상처럼 표현
- API 계약 분기
- 타인 데이터 REST·WebSocket 접근
- 알고리즘 변경에 따른 조용한 회귀

## 계층

```text
계약 검증
→ 백엔드 단위
→ MySQL 통합
→ 프론트 훅·컴포넌트
→ API Mock
→ 실제 E2E
→ 장시간·장애
```

## 계약 테스트

- OpenAPI 문법과 `$ref`
- operationId 중복
- 프론트 타입 생성
- Realtime JSON Schema
- fixture schema
- enum과 필드명

목표 스크립트: `scripts/validate-contracts.sh`

## Fixture

| 파일 | 목적 | 최소 기대 |
|---|---|---|
| frame-batch-normal | 정상 | accepted |
| frame-batch-left-asymmetry | 왼발 상대 증가 | 좌우 특징 증가 |
| duplicate | 재전송 | duplicate, 행 불변 |
| sequence-gap | 누락 | gap flag |
| sensor-stuck | 고정 | stuck flag |
| right-disconnected | 우측 중단 | disconnect flag |
| out-of-order | 순서 역전 | deviceTime/sequence 정렬 |

fixture는 임상 데이터가 아니라 기능 테스트용입니다.

## 백엔드 단위

### 상태
- CREATED→MEASURING
- CREATED→complete 실패
- MEASURING→PROCESSING
- COMPLETED→start 실패
- CANCELLED→ingest 실패

### 계산
- baseline 이하 0
- 정규화 상·하한
- CoP
- 압력 0이면 CoP null
- 대칭 지수 0나눗셈 방지
- contact 경계
- 6/8센서

### 규칙
- 기준 미만/동일/초과
- 데이터 부족
- quality POOR

## MySQL 통합

- 빈 DB Flyway
- FK·UNIQUE
- 동일 frame 중복 방지
- JDBC batch
- rollback 경계
- pagination
- algorithmVersion별 결과

핵심 쿼리를 H2만으로 끝내지 않습니다.

## API

- 미인증 401
- 타인 403
- 없음 404
- 상태 충돌 409
- 의미 검증 422
- Receiver Key 오류
- 부분 거절
- 크기 상한
- PROCESSING 202
- COMPLETED 200
- disclaimer

## WebSocket

서버:
- 인증·소유권
- schema
- 발행 실패와 DB 분리
- 10~20Hz 제한

프론트:
- 연결 상태
- 중복 구독 금지
- cleanup
- snapshot 복구
- LEFT/RIGHT
- 한쪽 발 중단

## E2E

`scripts/e2e_smoke.py`는 REST 생성·수집·snapshot·완료·결과·히스토리 흐름과 함께 실제
STOMP owner 구독, 다른 사용자 topic 거부, 연결 중단/재연결 뒤 신규 frame 수신, 한쪽 발만
계속 수집할 때 반대쪽 발의 disconnected flag와 마지막 값 보존을 자동 검증합니다. STOMP
프레이밍 파서는 `python -m unittest discover -s scripts -p "test_*.py"`로 독립 검증합니다.

### 정상
```text
가입/로그인 → 기기 → 세션 → fixture → 실시간
→ 종료 → 처리 중 → 결과 → 기록
```

### 중복
```text
같은 batch 2회 → duplicate 증가 → DB 행 불변
```

### 한쪽 발
```text
왼발 계속, 오른발 중단 → 오른발 경고 → 왼발 유지 → 품질 반영
```

### 권한
```text
A 세션 → B REST 403 → B topic 거절
```

### 재접속
```text
새로고침 → 세션 상세 → snapshot → 재구독
```

### 실기기 스케일 검증 (프론트 FE-5)

실시간 `sensorValues`는 세션 `adcMax` 기준 0~100 상대값이다(DEC-025). 프론트 Live 화면의
"세션 최대 센서 신호"(`leftPeak/rightPeak`)는 sessionId가 바뀔 때만 초기화되고, 토큰 교체로
STOMP 연결 effect가 다시 실행되어도 유지된다(`useRealtimeMeasurement.test.tsx`).

- 실기기 스케일 검증은 백엔드가 4095 스케일로 전환된 뒤(계약 1.1.0, V5 `adc_max`) 단계에서
  수행한다. 절차: 실기기 세션(`sourceType DEVICE`, 50Hz)에서 무부하 2초 → 직립 10초 → 보행 후,
  Live 화면의 세션 최대 신호가 무부하 구간에서 0 근처, 직립·보행 구간에서 100 미만(포화 아님)에
  머무는지와 `SENSOR_SATURATION`/`SENSOR_STUCK_OR_SATURATED` 플래그가 붙지 않는지 확인한다.
- 전환 전(레거시 65535 스케일 기기·세션)의 기대값 약 6 / `NO_CONTACT`는 회귀 관찰용 참고값일
  뿐이며 합격 기준이 아니다. 전환 전 값을 스케일 검증 결과로 기록하지 않는다.

## 성능 관찰

- 양발 초당 약 200프레임 입력
- HTTP 100~200ms 배치
- 1분 이상 지속 입력
- DB batch 처리 시간
- 10~20Hz 발행
- 브라우저 렌더·메모리
- 10분 이상 분석의 메모리 사용

실측 전 임의 성능 보장을 문서에 쓰지 않습니다.

## 장애

| 장애 | 기대 |
|---|---|
| 백엔드 중단 | Receiver 보관·재전송 |
| WebSocket 중단 | DB 저장 지속 |
| 분석 중 재시작 | job 복구 정책 |
| MySQL 실패 | 성공 응답 금지 |
| 토큰 만료 | 재로그인 안내 |
| 잘못된 fixture | 거절 내역 |

## 전체 검증 목표

```bash
./scripts/verify-all.sh
```

내용:
1. 계약
2. backend test/check
3. frontend lint/test/build
4. 선택 E2E

작업 보드는 구현·테스트·명령 성공·계약 일치가 모두 확인된 뒤 체크합니다.
