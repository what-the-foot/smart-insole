# 11. IMU 움직임 분석 로드맵 (정강이 장착, rule-v1.4.0)

2026-09-10 타당성 검토를 바탕으로 2026-09-11 제품 책임자가 확정한 장착 사실(DEC-036)에 맞춰 다시 쓴 로드맵입니다.
계약은 `contracts/openapi.yaml` 1.3.0의 `AnalysisResultResponse.movementSummary`, 의미는 `docs/02_API_AND_REALTIME_CONTRACT.md`,
저장은 `docs/03_DATA_MODEL.md`(V9)를 기준으로 합니다. 이 문서의 "[가정]" 표시는 저장소 밖 지식이나 아직 실측되지 않은 전제입니다.

## 0. 결론 요약

- IMU 원자료(`accelMg`, `gyroDps10`, `imuAvailable`)는 펌웨어 → 수신기 → 백엔드 `pressure_frames`까지 이미 프레임마다 저장되지만, rule-v1.3.0까지 어느 계층도 읽어 해석하지 않았습니다. 새 단계는 와이어·DB 구조를 바꾸는 일이 아니라 **해석 단계를 추가하는 일**입니다.
- IMU는 인솔 안이 아니라 **외측 발목/정강이에 스트랩으로 고정한 XIAO 보드**에 있습니다(DEC-036). 따라서 이 단계가 재는 것은 **정강이 분절**의 운동이며, 발의 내번/외번 각도나 발 진행각은 유도할 수 없으므로 산출하지도 표시하지도 않습니다.
- 보드 장착 방향은 정해져 있지 않으므로 **세션마다 자동 축 정렬**(정지 자세 가속도 = 수직 축, 자이로 주성분 = 내외측 축)을 수행합니다. 사용자는 측정 시작 후 약 2초 동안 가만히 서 있습니다(실시간 화면 카운트다운). 백엔드는 데이터에서 정지 구간을 스스로 찾으므로 이를 위한 API 변경은 없습니다.
- 산출물은 정강이 지표 4종 + 창 수이며 화면 용어는 **정강이 좌우 기울기(중간 입각기)**, **입각기 정강이 전후 회전 범위**, **입각기 정강이 수평 회전 범위**, **유각기 최대 각속도**입니다. 모두 **기능 검증용** 표기를 붙이고, 참조 계측 대비 오차를 얻기 전에는 사용자 화면에 "기능 검증용" 표기를 붙인 카드 이상으로 노출하지 않습니다(참고 범위·판정 문구 없음)(검증 게이트).

## 1. 지금 이미 흐르는 것 (사실)

| 계층 | 내용 | 근거 경로 |
|---|---|---|
| 펌웨어 | 온보드 LSM6DS3TR-C(I2C 0x6A)를 100 Hz 프레임마다 12 B 버스트 리드 1회로 읽어 가속도 int16 mg(±8 g), 각속도 int16 0.1 °/s(±500 dps)로 정수화. 축 리맵·부호 반전 없이 센서 X/Y/Z를 그대로 통과. BLE로는 50 Hz로 매 2번째 프레임의 최신 샘플만 전송(평균 없음, D-020 잠정) | `SmartInsoleFirmware_CodexReady/SmartInsoleFirmware/ImuReader.cpp:53-106`, `Config.h:53-78`, `DataTypes.h:21-26`, `docs/codex/DECISIONS.md:155-162` |
| 와이어 | Frame B(0xA2, 20 B) offset 8-13 accel XYZ, 14-19 gyro XYZ, meta bit2 = IMU available | `PacketCodec.cpp:23-42,86-94`, `docs/ble-packet-v1.md:63-93` |
| 수신기 | `<H2H6h`로 디코드해 `PressureSample.accel_mg/gyro_dps10/imu_available`로 싣고 openapi 1.1 배치의 `accelMg/gyroDps10/imuAvailable`로 전송. 단위 변환·필터·자세 추정 없음. CSV 레코더(기본 OFF)·SQLite Outbox에 원값 보존 | `smart-insole-ble-gateway-codex/src/smart_insole_gateway/protocol/v1_split.py:169-217,325-353`, `adapters/backend/openapi_v1_1.py:85-122`, `adapters/storage/csv_recorder.py:27-55`, `adapters/storage/sqlite_outbox.py:112-130`, `docs/ARCHITECTURE.md:203-208` |
| 백엔드 | V5가 `pressure_frames`에 `imu_available`, `accel_x/y/z_mg`, `gyro_x/y/z_dps10`, `flags`를 추가, 26컬럼 JDBC INSERT로 저장하고 `findBySessionOrdered`가 `StoredPressureFrame`으로 분석기에 넘김. rule-v1.3.0까지 `RuleBasedAnalyzer`·`RealtimeSnapshotStore`는 IMU 필드를 읽지 않음. `IMU_ERROR` 비트가 켜지면 `imuAvailable=false`로 정규화하되 벡터는 저장, 품질 플래그 `IMU_ERROR_REPORTED`는 감점 0(정보용) | `backend/src/main/resources/db/migration/V5__add_frame_metadata_and_receiver_columns.sql:1-14`, `backend/.../measurement/repository/PressureFrameRepository.java:28-41,187-224,276-292`, `backend/.../analysis/domain/RuleBasedAnalyzer.java:63-122,468-469`, `realtime/service/RealtimeSnapshotStore.java:126-139`, `measurement/service/PressureFrameIngestionService.java:172-257`, `measurement/domain/MeasurementQualityStats.java:152-190` |

압력·IMU는 같은 프레임의 `deviceTimeMs`를 공유하므로, 압력으로 정의한 접촉 창(유효 걸음)을 IMU 처리의 게이트(정지 구간 판정, 중간 입각기 선택, 유각기 구간)로 그대로 쓸 수 있다는 점이 결합의 핵심입니다.

## 2. 장착 사실과 그 의미 (DEC-036)

| 항목 | 타당성 검토 시점(2026-09-10) | 확정(2026-09-11) |
|---|---|---|
| 보드 위치 | 미기록. 후족/중족 강성 구역 권장 | **외측 발목/정강이(body-mounted)**, 인솔과 배선 연결 |
| 측정 분절 | 발(후족) 가정 | **정강이(shank)** |
| 축 규약 | 기구 도면 기반 고정 매핑표 필요 | 장착 방향 미정 → **세션별 자동 정렬** |
| 좌우 | 미러 여부 미정 | 양쪽 모두 외측 장착 → **거울 대칭**(lateral = 왼발 `+ml_left`, 오른발 `−ml_left`) |
| 산출 각도 | 내번/외번, 발 진행각 계획 | **산출 불가·미표시**. 대신 정강이 전두면 기울기, 입각기 전후·수평 회전 범위, 유각기 최대 각속도 |

정강이 장착이 뜻하는 것:

- 강체의 자세(회전)는 그 강체 위 어느 점에서 재도 같으므로, 스트랩이 단단히 고정되어 있으면 정강이 위 정확한 높이는 각도 지표에 영향이 없습니다. 반면 스트랩이 느슨하거나 피부·양말 위에서 미끄러지면 정강이가 아니라 "스트랩의 움직임"을 재게 됩니다. [가정] 재장착 편차는 검증 단계(§6)에서 수치화합니다.
- 발목 관절 아래(발)의 운동은 이 보드에 나타나지 않습니다. 따라서 압력 지표의 `MEDIAL/LATERAL_LOAD_TENDENCY`(하중 편중)와 정강이 좌우 기울기는 관련은 있으나 같은 것이 아니며, 어긋나는 경우가 있을 수 있습니다. UI 문구와 FAQ에 이를 미리 밝힙니다.
- 선가속도(충격) 지표는 레버암에 따라 값이 크게 달라지므로 이번 범위에서 제외합니다. 중간 입각기(각속도 ≈ 0)만 사용하는 기울기 지표는 이 영향을 무시할 수 있습니다.

## 3. 산출물 (계약 1.3.0, rule-v1.4.0)

`AnalysisResultResponse.movementSummary: MovementSummary | null`

| 필드 | 타입 | 정의 | null 조건 |
|---|---|---|---|
| `imuCoverage` | number 0..1 | 양발 저장 프레임 중 `imuAvailable=true`이고 accel·gyro 벡터가 모두 있는 프레임 비율 | 없음(객체가 있으면 항상 값) |
| `referenceMethod` | `QUIET_STANDING` \| `FIRST_STANCE` \| null | 기준 자세를 잡은 방법 | 기준을 잡지 못함 |
| `left` / `right` | `MovementFootSummary` \| null | 그 발 쪽 정강이 요약 | `imuCoverage < 0.5` 또는 그 발의 기준 자세 실패(축 정렬 실패·유각기 없음은 null이 아니라 `windowCount 0`) |
| `left.frontalTiltDeg` | number −180..180 \| null | 접촉 창 중간 입각기(30~60 %) 평균 가속도로 구한 `atan2(g·lateral, g·up)`(도)를 기준 자세 대비로 잡아 창 전체 평균. + 바깥쪽, − 안쪽 | 사용 가능한 창 없음 |
| `left.sagittalRangeDeg` | number ≥ 0 \| null | 창 안에서 `gyro·ml_left`를 사다리꼴 누적 적분한 각도 시계열 범위의 창별 중앙값 | 창 없음 |
| `left.transverseRangeDeg` | number ≥ 0 \| null | 같은 방식, `gyro·up` 성분 | 창 없음 |
| `left.swingPeakAngularVelocityDps` | number ≥ 0 \| null | 같은 발의 연속 창 사이(유각기) `\|gyro·ml_left\|` 최댓값의 구간별 중앙값 | 유각기 구간 없음 |
| `left.windowCount` | integer ≥ 0 | IMU를 사용할 수 있었던 접촉 창 수(정지 기준 자세 구간과 겹치는 창 제외) | 없음(0이면 위 4개가 null; 축 정렬 실패·유각기 없음 포함) |

객체 전체가 `null`: rule-v1.4.0 이전 결과, IMU 프레임이 없는 세션(예: 1.0 배치, 시뮬레이터). 백엔드는 키를 생략하지 않고 `null`로 내보냅니다(DEC-023).
저장은 V9 `analysis_results.movement_summary_json`(객체 전체 JSON) 하나이며 기록 목록 projection은 없습니다.

## 4. 파이프라인 단계

BE-011 파이프라인(품질 → 보정 → 노이즈 처리 → 접촉 이벤트 → 특징 추출 → 규칙 평가 → 추천 → 저장, `docs/04_BACKEND_IMPLEMENTATION_PLAN.md`)에서 접촉 이벤트 뒤·특징 추출 앞에 IMU 처리를 넣습니다. 걸음 정의는 계속 압력 접촉 창이며 IMU는 그 창의 정강이 운동만 보충합니다.

| 단계 | 내용 | 상태 |
|---|---|---|
| [0] 장착 규약 | 보드가 외측 발목/정강이에 스트랩 고정, 방향 미정, 좌우 거울 대칭 — DEC-036으로 기록. 펌웨어 쪽 D-0xx에는 "센서 축 그대로 통과, 정렬은 백엔드"만 남기면 됨 | 완료(문서) |
| [1] 펌웨어 확인 | 코드 변경 없음(`ImuReader.cpp:66-77` 직접 대입 유지). 실기기에서 정지 시 한 축 ≈ ±1000 mg, 회전 시 gyro 반응, 버스트 리드 < 1 ms 확인(`docs/codex/HARDWARE_TEST_CHECKLIST.md:158-179`, 현재 NOT RUN `docs/codex/VALIDATION_RESULTS.md:361-389`). 골든 벡터(`tests/fixtures/ble-v1/frame_b_golden.json`)에 "실기기 정지 자세" 1건 추가 후 `python tools/export_golden_vectors.py --check`. [선택] IMU ODR 104 Hz vs 100 Hz 폴링의 중복/누락 샘플은 후속 과제로 기록만 | 미착수(실기기 필요) |
| [2] 로딩·게이팅 | `StoredPressureFrame`의 `imuAvailable/accelMg/gyroDps10` 사용. `imuAvailable=false` 또는 벡터 null 프레임은 제외하고 `imuCoverage` 계산. 단위 g = mg/1000, °/s = dps10/10, dt = `deviceTimeMs` 차분(기본 20 ms @50 Hz, 10 ms @100 Hz). 발별 프레임은 수신기가 unwrap한 `sequence` 순으로 정렬해 `deviceTimeMs`가 뒤로 가는 RESET 지점(수신기 `docs/BLE_PROTOCOL.md:128-135`)이 음의 dt로 드러나게 하고, 그 지점에서 접촉 창을 닫고 적분 상태 초기화. int16 포화(`\|값\| ≥ 32760`) 프레임은 해당 창에서 제외 | 구현 중 |
| [3] 기준 자세 | `QUIET_STANDING`: 세션 첫 프레임부터 첫 1.0 s 이상 구간에서 모든 프레임이 `\|gyro\| < 10 dps`, `\|\|a\|−1 g\| < 0.1 g`, 양발 압력 접촉(기존 `contact-total-threshold`). up = 정규화 평균 accel, 자이로 바이어스 = 평균 gyro. 폴백 `FIRST_STANCE`: 발별 처음 3개 접촉 창의 중간 입각기(30~60 %) 프레임, 바이어스 0. 양발 모두 실패하면 `referenceMethod=null`, `left/right=null`. 정지 구간은 창 지표·`windowCount`에서 제외 | 구현 중 |
| [4] 축 자동 정렬 | 발별로 e = 바이어스 보정 gyro 전 프레임의 주성분(PCA) 벡터를 up과 직교화·정규화. 부호: 유각기 프레임(같은 발의 연속 창 사이) `median(gyro·e) > 0`이면 `e = −e`(앞으로 내딛는 회전이 e 기준 음의 회전). `ml_left = e`, `forward = ml_left × up`, lateral = 왼발 `+ml_left`, 오른발 `−ml_left` | 구현 중 |
| [5] 지표 계산 | 창별: `frontalTiltDeg`(중간 입각기 평균 accel 정규화 g, `atan2(g·lateral, g·up)`, 기준 자세 대비, 창 평균), `sagittalRangeDeg`(창 내 `gyro·ml_left` 사다리꼴 누적 적분 범위의 중앙값), `transverseRangeDeg`(`gyro·up` 동일), `swingPeakAngularVelocityDps`(창 사이 `\|gyro·ml_left\|` 최댓값의 중앙값). 창 단위로만 적분하고 세션 전체 연속 적분은 하지 않음(드리프트 누적 방지). 융합 필터(상보/Madgwick)는 창 단위 적분으로 충분하다고 보고 도입하지 않음 [가정] | 구현 중 |
| [6] 산출·저장·버전 | `movementSummary`를 결과 DTO와 V9 `movement_summary_json`에 저장, `algorithmVersion` rule-v1.3.0 → rule-v1.4.0(DEC-023: 과거 결과 null, 재해석 금지). 임계값(정지 판정 1.0 s/10 dps/0.1 g, 커버리지 0.5, 중간 입각기 30~60 %, 포화 32760, 폴백 창 3개)은 `AnalysisProperties`에 두고 기존 `defaults-are-functional-test-values: true` 플래그를 유지 | 구현 중 |
| [7] 계약·프론트 | `openapi.yaml` 1.3.0(선택·nullable 필드만, `additionalProperties:false` 유지, DEC-024 규칙) → 수신기 무변경(수신 계약 아님, PINS 갱신도 불필요) → 프론트 `npm run api:generate`로 `schema.ts` 재생성, `types.ts` alias 갱신, 결과 화면 "정강이 움직임(기능 검증용)" 카드(객체 null이면 미제공 안내 "이 세션에는 IMU 데이터가 없어 움직임 분석을 제공하지 않습니다."만 표시), 실시간 화면 시작 후 2초 정지 카운트다운. `scripts/validate_contracts.py`의 "Contract 1.3 movement summary policy"가 nullable·타입·범위·용어를 검사 | 계약 완료, 구현 중 |
| [8] 단위 테스트 | 합성 궤적(알려진 기울기·회전으로 만든 accel/gyro 시계열 + 접촉 창)으로 축 정렬 부호, 기울기 부호(+ 바깥쪽), 범위·최대 각속도, 포화 제외, RESET 초기화, 커버리지 < 0.5 → null, 기준 실패 → null을 결정적으로 검증 | 구현 중 |
| [9] 검증(노출 전 필수) | §6 참조. 통과 전에는 API에는 값을 내되 프론트 카드는 기능 검증용 표기로만, 참조 범위·판정 없음 | 미착수 |
| [10] 3-repo PR 흐름 | (1) 백엔드 PR: 계약 1.3.0 + 분석기 rule-v1.4.0 + V9 + 테스트 + DEC-036/이 문서; (2) 펌웨어 PR: 축 통과 원칙 한 줄·정지 자세 골든 벡터·HARDWARE_TEST 결과(코드 변경 거의 없음); (3) 수신기: 변경 없음(vendored 1.1.0 핀 유효), 문서에 "IMU 축은 펌웨어 원축, 정렬은 백엔드" 한 줄. 프론트는 백엔드 PR 뒤 | 진행 중 |

## 5. 압력 지표와 무엇이 다른가

| 항목 | 압력 지표 (rule-v1.2.0~1.3.0) | 정강이 IMU 지표 (rule-v1.4.0) |
|---|---|---|
| 물리량 | 발바닥 아래 신호 분포(무차원 비율, 0..1 추정 압력중심, 좌우 신호 비율) | 정강이 분절의 기울기(도), 회전 범위(도), 각속도(°/s) |
| 기준 | 센서 레이아웃 좌표(V6) — 있음 | 세션별 정지 자세·자동 축 정렬 — 이번에 추가 |
| 드리프트 | 없음(프레임 독립) | 자이로 적분 → 창 단위 적분·정지 구간 바이어스 제거로 제한 |
| 좌우 비교 | 레이아웃이 좌우 동일 의미(미러) | 거울 대칭 장착 → lateral 부호를 발별로 뒤집어 통일 |
| 걸음 정의 | 접촉 창 = 걸음 | 동일(압력이 정의, IMU는 보충) |
| 검증 상태 | 기능 검증용 임계값, 임상 미검증 | 실기기 IMU 검사 NOT RUN, 참조 계측 비교 없음 |
| 표현 규칙 | 중립 "경향" 표현(DEC-013) | 동일 + "정강이"·"기능 검증용" 표기, 발 관절 각도 표현 금지 |

두 지표는 보완재이며 서로 대체하지 못합니다. 하중 편중은 자세 변화 없이 체중 이동만으로도 생기고, 정강이 기울기는 하중을 모릅니다. [가정] 어긋남이 "있을 수 있음"을 UI·FAQ에 명시합니다.

## 6. 검증 게이트 (사용자 노출 전 필수)

1. **벤치**: 보드를 각도기/지그에 고정해 0/±5/±10/±20° 기울기에서 `frontalTiltDeg` 오차, 알려진 회전에서 `sagittalRangeDeg`·`transverseRangeDeg` 오차, 알려진 각속도에서 `swingPeakAngularVelocityDps` 오차를 확인.
2. **자동 정렬**: 같은 보드를 스트랩 위에서 여러 방향(예: 4방향)으로 돌려 장착해 같은 걸음에서 지표가 일치하는지 확인(정렬 알고리즘의 목적 검증).
3. **사람**: 소수 피험자, 고니오미터 또는 마커 기반 시스템의 정강이 각도와 비교(평균±SD, ICC).
4. **반복성**: 같은 사람 재장착 3회 시 결과 편차.
5. 목표 오차 범위(예: ±3~5°)는 [가정]이며 PO·임상 자문과 합의합니다. 통과 전에는 카드에 "기능 검증용" 표기만 두고, 통과 후에도 "경향이 관찰되었습니다" 수준의 중립 문구와 오차 범위 병기, 등급 부여 없음(DEC-013). 패턴 코드 승격(예: 정강이 기울기 경향)은 별도 DEC와 검증 데이터가 있어야 합니다.

## 7. 노력 추정과 프로젝트 리스크

[가정] 1인 기준, 피험자 섭외 제외.

| 작업 | 규모 | 비고 |
|---|---|---|
| 0. 장착 규약·DEC 기록 | 완료 | DEC-036 |
| 1. 펌웨어 축 원칙 문서 + 실기기 IMU 검증 + 정지 골든 벡터 | 2~3일 | 코드 변경 거의 없음; 실기기 필요(현재 NOT RUN) |
| 2. 백엔드 IMU 처리(기준 자세, 자동 정렬, 4개 지표) + 합성 궤적 단위 테스트 | 2~3주 | 삽입점·데이터는 준비됨. 발 각도 대신 정강이 지표이므로 융합 필터가 빠져 검토 시점보다 단순 |
| 3. 계약 1.3.0 + rule-v1.4.0 + 프론트 카드·카운트다운 | 1주 | DEC-023/024 규칙 그대로 |
| 4. 벤치·정렬·소규모 인체 검증 + 오차 보고서 | 2~4주 | 장비·피험자 확보가 병목; 통과 전 기능 검증용 표기 |
| **합계** | **약 6~9주** | 병렬화 시 캘린더 6주 안쪽 가능 |

주요 리스크:

1. **스트랩 고정·재장착**: 정강이 위 보드가 흔들리면 정강이가 아닌 스트랩 운동을 재게 됩니다. 재장착 편차를 검증 4로 수치화하기 전에는 세션 간 비교를 권하지 않습니다.
2. **자동 정렬 실패 모드**: 걸음이 거의 없거나(창 수 적음) 제자리 걷기처럼 회전이 한 축에 몰리지 않으면 PCA 주성분이 불안정합니다. 이때 그 발은 `windowCount 0`·네 지표 null로 두고 `referenceMethod`는 유지하며(계약 1.3.0), 단위 테스트에 접촉 창 1개짜리 짧은 세션과 정지만 있는 세션을 포함합니다.
3. **정지 구간 미검출**: 사용자가 시작 직후 바로 걸으면 `FIRST_STANCE` 폴백(바이어스 0)으로 넘어가 자이로 바이어스가 남습니다. 카운트다운 UI가 이를 줄이지만 `referenceMethod`를 결과에 남겨 구분합니다.
4. **하드웨어 미검증**: 실기기 IMU 검사 전부 NOT RUN(`docs/codex/VALIDATION_RESULTS.md:361-389`), 수신기 30분 soak·MTU 실측 NOT_RUN. 노이즈·포화·시간 지터 실측이 없습니다.
5. **샘플링/시간축**: 50 Hz 전송(매 2번째 샘플), IMU ODR 104 Hz vs 100 Hz 폴링(`ImuReader.cpp:53-77`) → 간헐적 중복/누락. 적분 오차 상한을 검증에서 수치화하고, RESET 시 적분 초기화를 반드시 지킵니다.
6. **IMU 가용성**: 프레임 단위 IMU_ERROR는 BLE로 나가지 않고 백엔드가 `imuAvailable=false`로 정규화합니다. 펌웨어는 IMU 불가 후 재초기화 경로가 없으므로 세션 중 IMU 소실은 `imuCoverage`로 드러나야 합니다.
7. **표현 규제**: D-010/DEC-013. 정강이 각도라도 그 자체로 수치이므로 검증된 오차 범위와 함께, 판정 없이 표시합니다. 발 관절(내번/외번) 표현으로 바꿔 쓰는 것을 금지합니다.
8. **범위 확장 유혹**: 걸음 검출을 IMU로 옮기면 기존 6종 패턴의 의미가 흔들립니다. "압력이 걸음을 정의, IMU는 정강이 운동만 보충"으로 고정합니다.

## 8. 권고

1. **지금**: 계약 1.3.0·DEC-036·V9를 기준으로 백엔드 rule-v1.4.0을 구현하고 합성 궤적 테스트로 부호·null 정책을 고정합니다.
2. **저비용 선행**: 실기기 IMU 검사를 실행해 VALIDATION_RESULTS를 NOT RUN에서 PASS/FAIL로 바꾸고, 수신기 CSV 레코딩(기본 OFF)을 켜서 정강이 장착 상태의 실제 보행 IMU 원자료를 몇 세션 확보한 뒤 오프라인에서 정렬·지표를 튜닝합니다.
3. **검증 게이트** 통과 전에는 "정강이 움직임(기능 검증용)" 카드 이상으로 노출하지 않습니다. 통과 후에도 중립 문구·오차 범위 병기·등급 없음.
4. **범위 고정**: 정강이 지표 4종만. 발 관절 각도·발 진행각·충격 지표는 장착 위치가 바뀌거나 별도 센서가 생기기 전에는 다루지 않습니다.
