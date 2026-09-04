# 04. 백엔드 구현 계획

## 목표 패키지

```text
com.smartinsole
├── global
│   ├── config
│   ├── error
│   ├── security
│   └── common
├── auth
├── user
├── device
├── calibration
├── measurement
├── realtime
├── analysis
└── recommendation
```

## BE-001 프로젝트 기반

### 구현
- Java 21/Gradle Spring Boot
- Web, Validation, JPA, JDBC, Security, WebSocket, Actuator
- MySQL, Flyway
- JUnit, Testcontainers
- local/test profile
- 환경변수 placeholder
- Docker Compose 연동
- UTC `Clock` Bean
- Health endpoint

### 완료
```text
./gradlew test
./gradlew check
bootRun 및 /actuator/health
빈 DB Flyway 적용
secret 없음
```

## BE-002 공통 오류·검증

- `ErrorCode`
- `BusinessException`
- `ErrorResponse`
- `GlobalExceptionHandler`
- Bean Validation 변환
- 401/403 형식
- trace ID
- 내부 예외·SQL 미노출

테스트: 잘못된 JSON, 누락값, 비즈니스 예외, 예상 밖 오류.

## BE-003 인증

- User 엔티티와 migration
- 회원가입·로그인
- PasswordEncoder
- JWT 생성·검증
- SecurityConfig와 필터
- 현재 사용자 식별자
- CORS 환경별 설정

필수:
- 이메일 중복 409
- 비밀번호 hash
- 미인증 401
- 위조·만료 토큰 거절
- 토큰·비밀번호 로그 금지

제외: Refresh Token, 소셜 로그인, 비밀번호 찾기.

## BE-004 SensorLayout·Device·Calibration

- 모델·migration
- `layout-v1` seed
- 기기 등록·내 기기 목록
- 센서 배치 조회
- 6/8센서
- serialNumber 중복
- 사용자 소유권
- LEFT/RIGHT
- 활성 보정 프로필

## BE-005 MeasurementSession

- 생성·시작·종료·취소
- 상세·페이지 목록
- 상태 도메인 메서드
- 양발 기기 소유권·방향
- 같은 기기 양쪽 선택 금지
- 측정 당시 calibration ID 고정

허용 전이:

```text
CREATED → MEASURING
CREATED → CANCELLED
MEASURING → PROCESSING
MEASURING → CANCELLED
MEASURING → FAILED
PROCESSING → COMPLETED
PROCESSING → FAILED
```

## BE-006 Receiver와 Frame Batch

- Receiver API Key
- PressureFrame migration
- 요청·프레임 검증
- JDBC Batch
- 중복 집계
- 부분 거절
- lastSequenceByDevice
- 최대 200프레임 기본
- 수신 통계 연결

구조:

```text
PressureFrameBatchController
→ PressureFrameIngestionService
  → ReceiverAuthorizer
  → SessionValidator
  → FrameValidator
  → BatchRepository
  → QualityTracker
  → RealtimeSnapshotUpdater
```

테스트:
- 정상 양발
- 동일 재전송
- 일부 중복
- 6/8센서
- footSide 오류
- 타 세션 기기
- 잘못된 상태
- schema 오류
- 배치 상한
- gap/out-of-order

## BE-007 품질 통계

검사:
- 예상 대비 수신률
- sequence gap
- 중복·거절률
- 계속 낮거나 높은 센서
- 한쪽 발 장시간 미수신
- deviceTime 역전·큰 점프

출력:

```text
score 0~100
level GOOD | ACCEPTABLE | POOR
flags[]
```

동시 요청에서도 통계 유실이 없어야 합니다.

## BE-008 WebSocket·Snapshot

- `/ws`
- 세션 topic
- JWT CONNECT
- 구독 소유권
- 최신 left/right snapshot
- REST realtime snapshot
- 발행 실패와 DB transaction 분리
- lifecycle 정리

## BE-009 실시간 계산

- calibration
- 0~100 정규화
- total pressure
- sensor layout 기반 CoP
- contact state
- lastReceivedAt 기반 connected
- quality 포함
- 10~20Hz 발행 제한
- 6/8센서
- JSON Schema 일치

## BE-010 AnalysisJob

- 종료 시 PENDING 생성
- PENDING/RUNNING/COMPLETED/FAILED
- algorithmVersion
- attempt count
- 중복 작업 방지
- 재시작 복구 가능한 DB 상태

## BE-011 분석 파이프라인

```text
QualityCheckProcessor
→ CalibrationProcessor
→ NoiseFilterProcessor
→ ContactEventDetector
→ FeatureExtractor
→ RuleEvaluator
→ RecommendationMapper
→ ResultWriter
```

원본 불변, 데이터 부족 처리, 동일 입력의 결정성을 보장합니다.

## BE-012 특징과 규칙

초기 특징:
- 유효 걸음 수
- cadence
- 양발 평균 접촉 시간
- symmetry index
- heel/midfoot/forefoot 비율
- medial/lateral 비율
- peak pressure
- CoP 요약

초기 패턴:
- `LEFT_RIGHT_ASYMMETRY`
- `MEDIAL_LOAD_TENDENCY`
- `LATERAL_LOAD_TENDENCY`
- `HIGH_MIDFOOT_LOAD`
- `SHORT_CONTACT_TIME`
- `LOW_DATA_QUALITY`

임계값은 버전 설정에 둡니다. 근거가 확정되지 않은 초기값은 테스트용 설정임을 명시합니다.

## BE-013 결과·추천

- analysis_results
- patterns
- recommendations
- PROCESSING 202 / COMPLETED 200
- evidence
- disclaimer
- algorithmVersion
- 패턴-운동 매핑

## BE-014 기록

- 사용자별 pagination
- 세션 상세
- 결과
- size 상한
- N+1 방지
- 원본 무제한 조회 금지

## BE-015 운영

- 구조화 로그
- 센서 배열 로그 제한
- Actuator
- CORS
- request size
- Dockerfile
- 운영 profile
- migration·백업 절차

## 권장 순서

```text
BE-001 → BE-002 → BE-003 → BE-004 → BE-005 → BE-006
                                      ├→ BE-008 → BE-009
                                      └→ BE-007 → BE-010 → BE-011
                                                     → BE-012 → BE-013 → BE-014 → BE-015
```

## 완료 체크

- [ ] Flyway 빈 DB 성공
- [ ] JWT와 사용자 소유권
- [ ] 기기·세션 상태
- [ ] 멱등 batch 저장
- [ ] 6/8센서
- [ ] gap·품질
- [ ] 양발 snapshot
- [ ] 타인 topic 차단
- [ ] 분석 재시도
- [ ] algorithmVersion
- [ ] 기록 pagination
- [ ] Testcontainers
- [ ] `./gradlew check`
