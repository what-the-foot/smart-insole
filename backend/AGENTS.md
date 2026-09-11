# Backend AGENTS.md

루트 `AGENTS.md`와 함께 적용한다.

## 책임

- 사용자 인증과 권한
- 기기, 센서 배치, 보정 프로필
- 측정 세션 상태
- Receiver 프레임 검증과 배치 저장
- 실시간 snapshot과 STOMP 발행
- 데이터 품질과 규칙 기반 분석
- 결과·운동·기록 API

BLE 연결과 BLE 바이너리 파싱은 Python Receiver의 책임이다.

## 구조

```text
com.smartinsole
├── SmartInsoleApplication
├── global                      # 도메인 공통, 구조 유지
│   ├── common                  # DomainTypes(enum)
│   ├── config                  # app.* 속성 record, Async·Time 설정
│   ├── error                   # ErrorCode, BusinessException, GlobalExceptionHandler, TraceIdFilter
│   └── security                # SecurityConfig, JWT·Receiver Key 필터
├── auth
│   ├── controller              # AuthController
│   ├── service                 # AuthService, SeedAccountInitializer
│   └── dto                     # AuthDtos
├── user
│   ├── domain                  # UserAccount
│   └── repository              # UserRepository
├── device
│   ├── controller              # DeviceController
│   ├── service                 # DeviceService
│   ├── domain                  # Device, SensorLayout
│   ├── repository              # DeviceRepository, SensorLayoutRepository
│   └── dto                     # DeviceDtos
├── calibration
│   ├── domain                  # CalibrationProfile
│   └── repository              # CalibrationProfileRepository
├── measurement
│   ├── controller              # MeasurementController, PressureFrameBatchController, ReceiverSessionController
│   ├── service                 # MeasurementService, PressureFrameIngestionService, QualityService, ReceiverSessionQueryService
│   ├── domain                  # MeasurementSession, MeasurementQualityStats, PressureFrameEntity
│   ├── repository              # MeasurementSessionRepository, PressureFrameRepository, MeasurementQualityRepository, HistoryProjectionRepository
│   └── dto                     # MeasurementDtos, IngestionDtos
├── realtime
│   ├── controller              # RealtimeController
│   ├── service                 # RealtimeService, RealtimeSnapshotStore, RealtimeDisconnectScheduler
│   ├── config                  # WebSocketConfig
│   ├── security                # StompAuthorizationInterceptor
│   └── dto                     # RealtimeDtos
├── analysis
│   ├── controller              # AnalysisResultController
│   ├── service                 # AnalysisCoordinator, AnalysisRunner, AnalysisJobStateService, AnalysisPersistenceService, AnalysisResultQueryService, AnalysisPendingJobScheduler
│   ├── domain                  # AnalysisJob, AnalysisResult, AnalysisPattern, RuleBasedAnalyzer, PatternCatalog
│   ├── repository              # AnalysisJobRepository, AnalysisResultRepository, AnalysisPatternRepository
│   └── dto                     # AnalysisDtos
└── recommendation
    ├── controller              # RecommendationController
    ├── service                 # RecommendationService
    ├── domain                  # Recommendation, ResultRecommendation
    ├── repository              # RecommendationRepository
    └── dto                     # RecommendationDtos
```

도메인 패키지 아래에 `controller`, `service`, `domain`, `repository`, `dto` 계층 패키지를 둔다. 도메인에 없는 계층은 만들지 않는다(`user`, `calibration`은 `domain`·`repository`만).

- 스케줄러와 부트스트랩 러너(`AnalysisPendingJobScheduler`, `RealtimeDisconnectScheduler`, `SeedAccountInitializer`)는 `service`
- 계산 규칙(`RuleBasedAnalyzer`, `PatternCatalog`)은 `analysis.domain`
- WebSocket 브로커 설정은 `realtime.config`, STOMP 인가 인터셉터는 `realtime.security`
- 테스트는 대상 클래스와 같은 패키지에 둔다(`src/test/java/com/smartinsole/<domain>/<layer>/`)

## 계층 규칙

### Controller
- HTTP 파싱, Bean Validation, 인증 주체 전달, 응답 변환만 담당
- Repository 직접 호출 금지
- 엔티티 직접 노출 금지

### Service
- 유스케이스 순서, 상태 전이, 소유권, 트랜잭션 경계 관리
- 큰 서비스는 유스케이스별로 분리

### Domain/Analyzer
- 계산과 상태 규칙을 가능한 순수 함수·독립 객체로 구현
- 임계값은 버전 설정으로 관리
- `algorithmVersion`과 중립 패턴 코드 보존

### Repository
- 일반 CRUD는 JPA
- `pressure_frames` 대량 입력은 JDBC Batch
- 프레임마다 `save()`/`saveAndFlush()` 반복 금지

## 엔티티·DTO

- 요청·응답 DTO는 Java `record` 우선 검토
- 엔티티 public setter 남발 금지
- 의도가 드러나는 생성·상태 변경 메서드 사용
- enum은 문자열 저장
- 서버 시간은 `Instant`, 테스트에는 `Clock`
- migration과 엔티티를 함께 변경

## 수신 규칙

- `schemaVersion` 검증
- 세션이 `MEASURING`인지 검증
- 세션에 배정된 기기인지 검증
- 기기의 방향과 `footSide` 일치
- 배열 길이와 `device.sensorCount` 일치
- ADC 범위는 공통 명세 기준
- `(session_id, device_id, sequence_no)` UNIQUE
- 중복은 무시하고 개수 보고
- 실제 순서는 `sequence`/`deviceTimeMs`
- 배치 크기 상한 설정

## 분석 규칙

```text
품질 → 보정 → 노이즈 처리 → 접촉 이벤트
→ 특징 추출 → 규칙 평가 → 추천 → 저장
```

- 원본 프레임 불변
- 보정·필터값은 별도 컨텍스트
- 데이터 품질 미달 시 신뢰도 하향 또는 재측정 안내
- `LEFT_RIGHT_ASYMMETRY`, `MEDIAL_LOAD_TENDENCY` 같은 중립 코드 사용
- 임계값을 임상적으로 검증됐다고 근거 없이 주장하지 않음

## 트랜잭션

- Controller에 `@Transactional` 금지
- 조회는 `readOnly=true` 검토
- WebSocket 발행 실패로 이미 저장된 원본을 롤백하지 않음
- 분석 작업은 재시도 가능하고 중복 실행에 안전하게 설계

## 오류 형식

```json
{
  "code": "SESSION_NOT_MEASURING",
  "message": "현재 측정 중인 세션이 아닙니다.",
  "details": {},
  "traceId": "...",
  "timestamp": "2026-09-02T07:00:00Z"
}
```

오류 코드는 중앙 관리한다.

## 테스트

- 상태 전이 정상·실패
- 계산 경계값
- Controller 인증·상태 코드·오류 본문
- 핵심 Repository와 Flyway는 MySQL Testcontainers
- 중복, gap, out-of-order, 6/8센서
- fixture 재사용
- 시간 테스트는 `Clock.fixed()`

## 검증

```bash
./gradlew test
./gradlew check
```

## Backend Review Rules

- migration 없이 컬럼만 변경했는가
- N+1 또는 원본 무제한 조회가 있는가
- pagination이 없는가
- 배치 상한이 없는가
- 요청의 `userId`를 신뢰하는가
- topic 이름만 알면 타인 구독이 가능한가
- 분석 재실행이 결과를 무분별하게 중복 생성하는가
