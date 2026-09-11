# 09. 결정 기록

- `ACCEPTED`: 구현 기준
- `PROPOSED`: 검토 중
- `DEFERRED`: MVP 이후

## DEC-001 저장소 — ACCEPTED
백엔드·프론트는 모노레포 권장. 별도 저장소면 계약 파일을 동일하게 유지합니다.

## DEC-002 백엔드 — ACCEPTED
Java 21 + Spring Boot + Gradle. 일반 CRUD JPA, 원본 대량 입력 JDBC Batch.

## DEC-003 프론트 — ACCEPTED
React + TypeScript + Vite. TanStack Query, Axios, STOMP. 기존 일관된 스택은 유지합니다.

## DEC-004 DB — ACCEPTED
MySQL + Flyway.

## DEC-005 센서 수 — ACCEPTED
기본 8, 기기 설정으로 6 지원. 배열 길이는 device.sensorCount와 같아야 합니다. DB는 최대 8개 컬럼을 둡니다.

## DEC-006 속도 — ACCEPTED
원본 100Hz, Receiver HTTP 100~200ms 배치, UI 10~20Hz.

## DEC-007 시간 — ACCEPTED
실제 순서는 `deviceTimeMs`와 `sequence`, `receivedAt`은 운영 추적. 서버는 UTC.

## DEC-008 원본 — ACCEPTED
ADC 원본 불변. 보정·필터·특징은 별도.

## DEC-009 멱등성 — ACCEPTED
`(session_id, device_id, sequence_no)` UNIQUE. 중복은 count 후 무시.

## DEC-010 인증 — ACCEPTED
사용자 JWT Bearer, Receiver API Key, STOMP 인증과 세션 소유권. Refresh Token은 후속 가능.

## DEC-011 실시간 — ACCEPTED
WebSocket + STOMP. 원본 저장소가 아님. 재연결은 topic을 먼저 재구독한 뒤 REST
snapshot을 조회하며, 구독 이후 새 메시지가 도착했다면 늦은 snapshot으로 덮어쓰지 않습니다.

## DEC-012 분석 — ACCEPTED
MVP는 규칙 기반. 품질→보정→필터→이벤트→특징→규칙→추천. 머신러닝 분리는 DEFERRED.

## DEC-013 표현 — ACCEPTED
질환 확정 금지. 중립 패턴 코드 사용.

## DEC-014 히트맵 — ACCEPTED
양발 동시, layout 좌표, 한쪽 끊김 시 다른 발 유지.

## DEC-015 운동 — ACCEPTED
패턴 코드와 추천 코드를 백엔드가 매핑. 치료 보장 금지.

## DEC-016 계약 우선 — ACCEPTED
OpenAPI와 Realtime JSON Schema가 단일 기준. 계약→백엔드→프론트→통합.

## DEC-017 UI — PROPOSED
신뢰감 있는 재활·운동 대시보드. 기존 디자인 시스템 우선.

## DEC-018 원본 보존 — DEFERRED
보존 기간·압축·삭제는 실제 운영 요구 후 결정.

## DEC-019 센서 좌표계 — ACCEPTED
발 로컬 좌표는 `x=0` 내측→`x=1` 외측, `y=0` 발가락→`y=1` 뒤꿈치로
정의합니다. 착용자 기준 양발 화면에서는 왼발 센서·CoP·윤곽을 수평 반전합니다.

## DEC-020 기록·추천 상세 계약 — ACCEPTED
운동 가이드는 코드별 상세 API로 직접 조회하고, 기록은 날짜·상태·최소
품질 점수·패턴 필터와 주요 패턴을 서버 pagination 계약에서 제공합니다.

## DEC-021 기본 보정 — ACCEPTED
하드웨어 없는 `SIMULATED` MVP는 기기 등록 시 `identity-v1` 기능 검증용 보정을
생성합니다. 이 프로필은 개인화·임상 보정을 의미하지 않으며, 실제 기기 보정
워크플로는 하드웨어 프로토콜 확정 후 별도 버전으로 추가합니다.

## DEC-022 비밀번호 byte 한계 — ACCEPTED
BCrypt에 전달하는 비밀번호는 문자 수 제한과 별도로 UTF-8 72 byte 이하인지 먼저
검사합니다. 초과 입력은 예외나 자동 절단 대신 `400 INVALID_REQUEST`로 명시적으로
거절하며 OpenAPI의 `x-maxUtf8Bytes`에 같은 제약을 기록합니다.

## DEC-023 분석 결과 버전 호환성 — ACCEPTED
특징 계산 의미가 바뀌면 `algorithmVersion`을 올립니다. `rule-v1.1.0`에서 유효 걸음 수,
중족부·전족부 비율, 최대 압력, 평균 CoP를 추가합니다. 기존 `rule-v1.0.0` 결과에 없던
값은 `null`로 반환하여 실제 0과 구분하고, 저장된 과거 결과를 새 의미로 재해석하지 않습니다.

## DEC-024 수신기 계약 1.1 단일 변경 — ACCEPTED
수신기→백엔드 변경(프레임별 `receivedAt`, `protocolVersion`, `dataMode`, `calibrated`,
`imuAvailable`, IMU 벡터, `flags`, `batchId`, 세션 조회 API, heartbeat 확장, receiver-status)은
`schemaVersion 1.1`·`openapi 1.1.0` 한 번으로 확정합니다. `additionalProperties: false`와
`fail-on-unknown-properties`는 유지하므로 값이 없는 키는 생략하고, 1.0 배치에 1.1 필드가 오면
프레임 단위 `SCHEMA_FIELD_NOT_ALLOWED`로 거절합니다. 1.0 배치는 계속 허용하며 이후 필드 추가는
optional만 허용합니다. 인증은 `X-Receiver-Key`를 그대로 씁니다.

## DEC-025 ADC 4095 단일 스케일 — ACCEPTED
`devices.adc_max`(등록 허용값 4095만)와 세션 생성 시 `measurement_sessions.adc_max` 스냅샷이
검증 상한·실시간 정규화·분석 정규화·포화 휴리스틱의 유일한 기준입니다. 자바 코드에 65535
상수는 없고, V5 이전 행만 SQL에서 65535로 백필하여 레거시 SIMULATED 데이터의 재현성을
보존합니다. `allow-legacy-adc-max` 같은 우회 옵션은 두지 않으며
`app.analysis.adc-max-value`는 세션 값이 없을 때의 기본값으로만 남깁니다.
접촉 임계값은 센서당 3.75(8센서 30.0)의 제안값이며 무부하·직립 실측 후 확정합니다.

## DEC-026 센서 레이아웃 layout-s01s08-v1 — ACCEPTED
펌웨어 순서 S01..S08(index = S번호 − 1 = MUX 채널)을 V6에서 seed하고 `SensorPoint.label`을
유일한 확장 키로 둡니다. 좌표는 도면 확정 전 제안값(±0.05 보정 예정)입니다.
`layout-v1`/`layout-v1-6`은 FK 보존을 위해 `active=false`로만 바꾸고 조회 API는 비활성
레이아웃도 반환하며 등록만 차단합니다. 6센서 seed는 어떤 두 센서를 빼는지, 엄지(S08)
유무, MUX 매핑을 하드웨어에서 한 번에 결정할 때까지 만들지 않습니다.

## DEC-027 sequence u32와 gap 산술 — ACCEPTED
백엔드는 수신기가 펼친 단조 u32(상한 4294967295)만 받습니다. 누락 수는 발별
`first/last sequence`로 Σ(last − first + 1) − 수신의 O(1) 산술로 유지하고, 세션 완료 시
저장 행 기준 `countSequenceGaps`로 1회 대조합니다. sequence가 설정 거리(기본 60000) 이상
감소했는데 `deviceTimeMs`는 증가하면 `SEQUENCE_WRAP_SUSPECTED`(감점 10)를 붙이며,
protocolVersion 2 이상(네이티브 u32)에서는 비활성입니다. wrap이 의심된 세션은 행 순서가
모호하므로 완료 시 대조 대신 산술값을 유지합니다.

## DEC-028 sourceType 명시·sampleRateHz 50/100 — ACCEPTED
`sourceType`은 자동 판별하지 않고 요청 필드로 받되 기본값은 `DEVICE`, 시뮬레이터(mock
receiver, e2e 스크립트, 개발용 UI)만 `SIMULATED`를 명시합니다. `receiverId`는 신뢰 기준이
아닙니다. `sampleRateHz`는 50(BLE 전송률, 측정 100Hz 분주) 또는 100만 허용하고 세션 값이
전송률의 단일 출처이며, `deviceTimeMs` 차분 중앙값이 ±30%를 벗어나면
`SAMPLE_RATE_MISMATCH`를 붙입니다.

## DEC-029 409 disposition — ACCEPTED
`SESSION_NOT_MEASURING`의 `details.disposition`이 수신기 처리의 1차 판별자입니다.
세션 CREATED면 `RETRY`(+`Retry-After`, 기본 2초), 그 외에는 `DROP`이며 DROP은 WARN
로그(sessionId, 프레임 수, receiverId)를 남깁니다. `X-Batch-Disposition` 헤더는 보조 정보이고
`disposition`이 없으면 수신기가 `currentStatus`로 폴백합니다. `BusinessException`에 응답
헤더 맵을 추가했을 뿐 공통 오류 본문은 바뀌지 않습니다.

## DEC-030 rule-v1.2.0 관찰 단계 — ACCEPTED
패턴 코드는 `MEDIAL_LOAD_TENDENCY, LATERAL_LOAD_TENDENCY, LEFT_RIGHT_ASYMMETRY,
LOW_HALLUX_SIGNAL, FOREFOOT_LOAD_TENDENCY, REARFOOT_LOAD_TENDENCY` 6종으로 고정합니다.
창은 유효 걸음(접촉 구간)이고 `LEFT_RIGHT_ASYMMETRY`는 `receiver_received_at`(없으면 배치
`received_at`→index) 순의 좌우 창 쌍입니다. `ObservationLevel`은 발생 비율
0.20/0.60과 최소 창 4로 정하며(모두 제안값), `patterns`에는 PARTIALLY/REPEATEDLY만
저장하고 `observationSummary`는 6종 전체를 담습니다. `HIGH_MIDFOOT_LOAD`·`SHORT_CONTACT_TIME`은
제거하고 `LOW_DATA_QUALITY`는 `dataQuality.flags`로 옮기며 `REMEASURE_GUIDE`는 품질 점수
임계로 추천합니다. 센서별 share(접촉 프레임 평균, 합 100)를 `pressureDistribution`에
넣고, 이전 버전 결과의 새 필드는 `null`로 둡니다(DEC-023).

## DEC-031 STOMP 만료 처리 — ACCEPTED
구독 중 CONNECT 토큰이 만료되면 outbound 메시지를 조용히 버리지 않고 STOMP `ERROR`
프레임(`message:TOKEN_EXPIRED`)을 한 번 보내고 연결을 끊습니다. 만료 토큰의
CONNECT/SUBSCRIBE도 같은 메시지로 거절합니다. 프론트는 이를 AUTH_EXPIRED로 매핑해
재로그인 후 재구독합니다. 서버 측 토큰 리프레시 API는 후속입니다.

## DEC-032 하드웨어 없는 gateway E2E 판정 지표 — ACCEPTED
`scripts/e2e_gateway_mock.py`는 수신기 `MetricsSnapshot`의 실제 필드명(`outbox_pending`,
`batches_terminal_failed`, `outbox_terminal_failed`, 큐 `overflows`)과 백엔드 응답만으로
판정합니다. 백엔드 `received_frame_count`는 API로 노출하지 않으므로 실시간 snapshot의
발별 `lastSequence`(START_AND_SYNC 후 0부터)로 프레임 수를 추정해 ±10%로 비교합니다.
이 스크립트는 MySQL·백엔드·수신기 CLI가 모두 있을 때만 실행하며 기본 검증 체인에서는 SKIP입니다.

## DEC-033 local 프로필 시드 계정 — ACCEPTED
`.env`의 `SEED_ADMIN_EMAIL`·`SEED_ADMIN_PASSWORD`(선택 `SEED_ADMIN_NAME`)가 있으면 `local` 프로필의
`SeedAccountInitializer`(ApplicationRunner)가 시작 시 `AuthService.signup`으로 계정을 한 번 만듭니다.
값이 비어 있거나 계정이 이미 있으면 건너뛰고, 비밀번호 8자 미만·이메일 형식 오류는 경고만 남긴 뒤
기동을 계속합니다. Flyway migration에 계정을 넣지 않는 이유는 비밀번호 해시가 모든 환경에 배포되기
때문입니다. 권한 모델이 없으므로 이 계정은 일반 사용자와 같고, `prod` 프로필에는 등록되지 않습니다.

## DEC-034 백엔드 패키지: 도메인 → 계층 — ACCEPTED
`com.smartinsole.<domain>` 아래에 `controller`, `service`, `domain`, `repository`, `dto` 하위 패키지를 두어
계층을 나눕니다(2026-09-10). 도메인에 없는 계층은 만들지 않습니다(`user`·`calibration`은 `domain`·`repository`만).
스케줄러와 부트스트랩 러너는 `service`, 계산 규칙(`RuleBasedAnalyzer`, `PatternCatalog`)은 `analysis.domain`,
WebSocket 설정은 `realtime.config`, STOMP 인가 인터셉터는 `realtime.security`에 둡니다. 테스트는 대상 클래스와
같은 패키지를 따릅니다. `global`은 그대로이며, 이동은 package·import 줄만 바꾸고 코드 본문은 바꾸지 않았습니다.

## DEC-035 rule-v1.3.0 좌우 신호 비율·스트라이드 시간, 계약 1.2.0 기록 요약 — ACCEPTED
**배경.** 결과 화면은 발별 센서 share와 접촉 시간은 보여 주지만 세션 전체에서 왼발·오른발 신호가 어떤 비율로
나뉘는지, 한 발의 걸음 주기가 얼마나 되는지는 주지 않았고, 추세 그래프를 그리려면 기록 목록 뒤에 세션마다 결과
조회를 한 번씩 더 해야 했습니다(2026-09-10, 제품 책임자 승인).

**결정.**
1. 분석기를 `rule-v1.3.0`으로 올리고 `pressureDistribution`에 `leftLoadSharePct/rightLoadSharePct`
   (각 발의 접촉 프레임 평균 전체합 `L`, `R`에 대해 `L/(L+R)×100`, `R/(L+R)×100`, 합 100), `gaitSummary`에
   `leftStrideTimeMs/rightStrideTimeMs/meanStrideTimeMs`(같은 발의 연속 접촉 구간 시작-시작 간격을
   `deviceTimeMs`로 계산한 중앙값, 평균은 둘 다 있으면 산술 평균·한쪽만 있으면 그 값)를 추가합니다.
2. `MeasurementHistoryItem`에 최신 분석 결과의 `algorithmVersion`, `dataQualityLevel`, `symmetryIndex`, `cadence`,
   `leftContactTimeMs`, `rightContactTimeMs`, `validStepCount`, `leftLoadSharePct`, `rightLoadSharePct`,
   `meanStrideTimeMs`를 nullable 선택 필드로 넣어 목록 호출 한 번으로 추세를 그립니다. 값은 `analysis_results`
   컬럼에서 조인하며 JSON을 파싱하지 않습니다(V8 `left/right_load_share_pct`, `left/right/mean_stride_time_ms`).
3. `openapi.yaml`은 `1.1.0 → 1.2.0` 추가 전용 변경입니다. 수신기가 vendoring한 1.1.0 핀은 그대로 유효하고
   프레임 배치·수신기 API는 바뀌지 않습니다.

**null 정책(DEC-023 유지).** 새 필드는 모두 nullable이며 `required`에 넣지 않습니다. 어느 한 발이라도 접촉 구간이
없으면 좌우 신호 비율은 둘 다 `null`(양발 센서 수가 다르면 두 값 모두 null), 발의 창이 2개 미만이면 그 발의 스트라이드는 `null`, 양발 모두 `null`이면
평균도 `null`입니다. `rule-v1.3.0` 이전 결과와 COMPLETED가 아닌 세션의 기록 요약은 `null`이며 과거 결과를
재계산하지 않습니다. 백엔드는 null 키를 생략하지 않고 `null` 값으로 내보냅니다.

**표시 용어(DEC-013 유지).** `loadSharePct`의 화면 용어는 **좌우 신호 비율**입니다. 힘·체중·압력을 측정한
값이 아니라 보정·평활 후 센서 신호의 상대 비율이므로 '하중', '체중 분포', '압력 비율'로 쓰지 않습니다.
스트라이드 시간은 **스트라이드 시간(추정)**으로 표기하고 접촉 구간 기반 추정이며 임상 검증된 보행 주기가 아님을
설명합니다. 두 지표 모두 참고 범위를 두지 않고 '정상'·'양호'·'개선' 같은 판정 문구를 붙이지 않습니다.
`scripts/validate_contracts.py`의 "Contract 1.2 gait metric policy"가 nullable·선택·범위·중립 문구를 검사합니다.

**결과.** 프론트는 1.2.0 타입을 재생성해 결과 화면과 기록 추세에 새 필드를 표시하고, 기록 화면은 세션별 결과 조회
없이 목록 응답만으로 그래프를 그립니다. `e2e_smoke.py` 등 `rule-v1.2.0`을 고정한 검사는 `rule-v1.3.0`으로 갱신해야
합니다. 스트라이드 간격은 전송 공백으로 창이 끊긴 경우도 포함될 수 있어 중앙값으로 영향을 줄이지만, 임상 지표로
해석하지 않는 조건에서만 사용합니다.

## DEC-036 rule-v1.4.0 IMU 정강이 움직임 요약, 계약 1.3.0 — ACCEPTED
**배경.** 계약 1.1부터 프레임마다 IMU 원자료(`accelMg`, `gyroDps10`, `imuAvailable`)가 `pressure_frames`에 저장되지만
rule-v1.3.0까지 어느 계층도 읽지 않았습니다. 2026-09-10 타당성 검토는 "장착 위치·축 규약이 없으면 각도를 낼 수 없다"고
결론지었고, 2026-09-11 제품 책임자가 장착 사실을 확정했습니다: IMU(LSM6DS3TR-C, XIAO nRF52840 Sense)는 인솔 안이 아니라
**외측 발목/정강이에 스트랩으로 고정한 보드**에 있고 인솔과 배선으로 연결되며, 장착 방향은 정해져 있지 않습니다.
양쪽 보드는 모두 외측에 붙어 거울 대칭입니다.

**결정.**
1. 이 단계가 재는 것은 **정강이(shank) 분절**의 운동입니다. 발의 내번/외번 각도와 발 진행각은 이 장착으로 유도할 수 없으므로
   산출하지 않고, 어떤 문구로도 주장하지 않습니다. 화면·계약 설명에는 **정강이**와 **기능 검증용**을 붙입니다.
2. 장착 방향이 미정이므로 **세션별 자동 축 정렬**을 합니다. 사용자는 시작 후 약 2초 동안 가만히 서 있고(실시간 화면
   카운트다운), 백엔드는 데이터에서 정지 구간을 스스로 찾으므로 이를 위한 API 변경은 없습니다.
   기준 자세 `QUIET_STANDING` = 세션 첫 프레임부터 첫 1.0 s 이상 구간에서 모든 프레임이 `|gyro| < 10 dps`,
   `||a|−1 g| < 0.1 g`, 양발 압력 접촉(기존 임계값); up = 정규화 평균 accel, 자이로 바이어스 = 평균 gyro.
   폴백 `FIRST_STANCE` = 발별 처음 3개 접촉 창의 중간 입각기(30~60 %) 프레임 평균, 바이어스 0.
   축: 발별 e = 바이어스 보정 자이로 전체의 주성분(PCA)을 up과 직교화·정규화, 유각기 프레임의 `median(gyro·e) > 0`이면
   `e = −e`(앞으로 내딛는 회전이 e 기준 음의 회전); `ml_left = e`, `forward = ml_left × up`,
   lateral = 왼발 `+ml_left`, 오른발 `−ml_left`.
3. 분석기를 `rule-v1.4.0`으로 올리고 `AnalysisResultResponse.movementSummary: MovementSummary | null`을 추가합니다.
   `MovementSummary { imuCoverage: number 0..1, referenceMethod: QUIET_STANDING | FIRST_STANCE | null,
   left: MovementFootSummary | null, right: MovementFootSummary | null }`,
   `MovementFootSummary { frontalTiltDeg: number −180..180 | null, sagittalRangeDeg: number ≥ 0 | null,
   transverseRangeDeg: number ≥ 0 | null, swingPeakAngularVelocityDps: number ≥ 0 | null, windowCount: integer ≥ 0 }`.
   - `frontalTiltDeg`(정강이 좌우 기울기(중간 입각기)): 창의 중간 입각기(30~60 %) 평균 accel 정규화 g로
     `atan2(g·lateral, g·up)`(도), 기준 자세 대비, 창 전체 평균. + 바깥쪽(lateral), − 안쪽(medial).
   - `sagittalRangeDeg`(입각기 정강이 전후 회전 범위): 창 안 `gyro·ml_left` 사다리꼴 누적 적분 각도의 범위, 창별 중앙값.
   - `transverseRangeDeg`(입각기 정강이 수평 회전 범위): 같은 방식, `gyro·up` 성분.
   - `swingPeakAngularVelocityDps`(유각기 최대 각속도): 같은 발의 연속 창 사이 `|gyro·ml_left|` 최댓값의 중앙값.
   - `windowCount`: IMU를 사용할 수 있었던 접촉 창 수(정지 기준 자세 구간과 겹치는 창은 제외).
   단위 g = mg/1000, °/s = dps10/10, dt = `deviceTimeMs` 차분(발별 프레임은 수신기가 unwrap한 `sequence` 순으로 정렬하므로
   RESET에서 `deviceTimeMs`가 뒤로 가면 접촉 창을 닫고 적분 초기화). int16 포화(`|값| ≥ 32760`) 프레임은
   해당 창에서 제외. 걸음 정의는 계속 압력 접촉 창이며 IMU는 그 창의 정강이 운동만 보충합니다.
4. `openapi.yaml`은 `1.2.0 → 1.3.0` 추가 전용 변경입니다(`additionalProperties: false` 유지). 프레임 배치·수신기 API는
   바뀌지 않아 수신기의 1.1.0 핀은 그대로 유효합니다. 저장은 V9 `analysis_results.movement_summary_json`(객체 전체 JSON)
   하나이며 기록 목록 projection은 두지 않습니다.

**null 정책(DEC-023 유지).** `movementSummary`는 nullable·선택 필드이며 `required`에 넣지 않습니다. 객체 전체가 `null`:
`rule-v1.4.0` 이전 결과, IMU 프레임이 없는 세션. `left/right`가 `null`: `imuCoverage < 0.5` 또는 그 발의 기준 자세를 잡지 못한 경우
(양발 모두 실패하면 `referenceMethod null`). 기준은 잡았지만 축 정렬을 못 하거나(자이로 회전 없음) 유각기 구간이 없어 부호를
정할 수 없으면, 또는 사용 가능한 창이 없으면 그 발은 `windowCount 0`과 네 지표 `null`(발 객체는 유지). 과거 결과는 재계산하지 않고
백엔드는 null 키를 생략하지 않고 `null` 값으로 내보냅니다. 임계값(정지 1.0 s/10 dps/0.1 g, 커버리지 0.5, 중간 입각기
30~60 %, 포화 32760, 폴백 창 3개)은 `AnalysisProperties`에 두고 기존 "functional-test defaults, not clinically validated"
플래그를 유지합니다. 구현은 결정적이어야 하며 합성 궤적 단위 테스트로 부호·null 정책을 고정합니다.

**표시 용어(DEC-013 유지).** 화면 용어는 **정강이 좌우 기울기(중간 입각기)**, **입각기 정강이 전후 회전 범위**,
**입각기 정강이 수평 회전 범위**, **유각기 최대 각속도**이며 카드 제목에 **정강이 움직임(기능 검증용)**을 붙입니다.
발 관절 각도(내번/외번), 발 진행각, 참고 범위, '정상'·'양호'·'개선'·'악화'·'위험'·'중등도' 같은 판정·등급 문구를
금지합니다. `scripts/validate_contracts.py`의 "Contract 1.3 movement summary policy"가 nullable·선택·타입·범위·용어를
검사합니다.

**검증 게이트.** 지그 벤치(0/±5/±10/±20°), 자동 정렬 재현성(보드를 여러 방향으로 장착), 소수 피험자 참조 계측
(고니오미터/마커) 비교, 재장착 3회 편차로 오차(평균±SD)를 얻기 전에는 사용자 화면에 "기능 검증용" 표기 이상으로
노출하지 않습니다. 통과 후에도 중립 문구·오차 범위 병기·등급 없음이며, 패턴 코드 승격은 별도 DEC와 검증 데이터가
필요합니다. 단계·위험·노력 추정은 `docs/11_IMU_MOVEMENT_ROADMAP.md`에 둡니다.

**결과.** 프론트는 1.3.0 타입을 재생성해 결과 화면에 정강이 움직임 카드(객체 `null`이면 카드에 미제공 안내
"이 세션에는 IMU 데이터가 없어 움직임 분석을 제공하지 않습니다."만 표시)와 실시간 화면의 시작 후 2초 정지 카운트다운을
추가합니다. `e2e_smoke.py` 등 `rule-v1.3.0`을 고정한 검사는 `rule-v1.4.0`으로 갱신해야 합니다.
IMU가 없는 1.0 배치·시뮬레이터 세션은 `movementSummary null`로 기존과 같이 동작합니다.

## 구현 중 계획과 달라진 점 (2026-09-04)
- V5 `pressure_frames` 메타 컬럼은 계획의 11개에 `flags`를 더한 12개(INSERT 26컬럼)입니다.
  `StoredPressureFrame`이 `flags`를 노출하려면 저장이 필요합니다.
- `RegisterDeviceRequest.adcMax` 오류는 `422 SEMANTIC_VALIDATION_FAILED`, `sampleRateHz` 오류도
  `422 SEMANTIC_VALIDATION_FAILED`로 통일했습니다(기기 선택 문제는 기존 `INVALID_DEVICE_SELECTION`).
- 1.1 프레임 검증 코드에 계획 목록 외 `INVALID_PROTOCOL_VERSION`(0 이하)을 추가했습니다.
- `flags` 비트와 `dataMode`는 품질 플래그 `FSR_ERROR_REPORTED`(감점 10)·`IMU_ERROR_REPORTED`·
  `BATTERY_LOW_REPORTED`·`FILTERED_DATA_MODE`(감점 5)로 드러나며, 프론트 FE-8 라벨 목록과 일치합니다.
- ApiFlow/MySQL 통합 테스트는 fixture가 발당 접촉 구간 1개라 `app.analysis.min-observation-windows=1`
  테스트 프로퍼티를 사용합니다(계획의 대안 중 하나).
- `MySqlIntegrationTest`(Testcontainers)는 이 환경에 Docker가 없어 SKIP되었습니다. 코드는
  `applied >= 7`, 1.1 왕복, `layout-s01s08-v1`, `rule-v1.2.0`으로 갱신했습니다.
- `scripts/e2e_gateway_mock.py`는 수신기 환경변수 이름을 `--gateway-env`로 덮어쓸 수 있게 두었습니다.
  수신기 `settings.py`의 최종 이름과 대조가 필요합니다.
