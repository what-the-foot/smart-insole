# 05. 프론트엔드 구현 계획

## 목표 구조

```text
src/
├── app/
├── api/
│   └── generated/
├── components/
├── features/
│   ├── auth/
│   ├── devices/
│   ├── measurement/
│   ├── realtime/
│   ├── results/
│   ├── history/
│   └── recommendations/
├── pages/
├── routes/
├── styles/
└── test/
```

## FE-001 프로젝트 기반

- React + TypeScript + Vite
- 현재 package manager 잠금 파일
- ESLint/formatter
- Vitest/RTL
- React Router
- TanStack Query
- Axios
- 환경변수 타입
- Layout/Error Boundary
- Mock Service Worker 검토

완료:

```text
npm run lint
npm run test -- --run
npm run build
```

## FE-002 계약 타입·API

- OpenAPI 타입 생성
- `npm run api:generate`
- 생성 파일 직접 수정 금지
- Axios 인증 인터셉터
- ApiError 변환
- auth/device/measurement API 함수
- URL 중앙화
- 날짜 유틸

## FE-003 인증 UI

- 로그인·회원가입
- 인증 상태
- 보호 라우트
- 로그아웃
- 만료·401 처리
- 토큰 로그 금지

## FE-004 앱 셸·대시보드

- Header/Nav
- 사용자 정보
- 새 측정 CTA
- 최근 측정
- 최근 결과
- 기기 요약
- Loading/Empty/Error
- Mock으로 독립 개발

## FE-005 기기·측정 준비

- 내 기기 목록
- LEFT/RIGHT 필터
- sensorCount/layout/status
- 양발 선택 form
- 같은 기기 금지
- 방향 오류 방지
- 보정 필요 안내

## FE-006 측정 제어

- 세션 생성
- 시작·종료·취소
- 중복 클릭 방지
- 상태별 버튼
- 오류 후 다음 행동 안내

## FE-007 STOMP

상태:

```text
IDLE
CONNECTING
CONNECTED
RECONNECTING
DISCONNECTED
ERROR
```

- JWT CONNECT
- 구독·해제
- 제한된 재연결
- session 변경 cleanup
- REST snapshot 복구
- 중복 구독 방지
- 잘못된 메시지 처리

## FE-008 실시간 측정

- 경과 시간
- 연결·품질 badge
- 왼발·오른발 히트맵
- 총압력, CoP, 접촉
- 좌우 비율
- 종료·취소
- 한쪽 발 중단
- 모바일 조작

히트맵:

1. layout 좌표 조회
2. 0~100 센서값 배치
3. SVG/Canvas 또는 단순 gradient
4. 범례와 숫자 요약
5. 6/8센서
6. LEFT/RIGHT 좌표 정책을 계약과 일치

성능:
- 서버의 10~20Hz 메시지 사용
- 100Hz history 무제한 누적 금지
- 전체 페이지 rerender 최소화

## FE-009 처리 중

- PROCESSING 화면
- 결과 API 제한 폴링
- 완료·실패 시 중단
- 재접속 복구

## FE-010 결과

- 요약
- 품질
- gait 지표와 단위
- 좌우·영역 분포
- 패턴과 evidence
- 추천
- disclaimer
- 패턴 없음과 “정상 확정” 구분

## FE-011 운동 가이드

- 운동 목적
- 수행 단계
- 시간·횟수
- 주의
- 통증 시 중단
- 치료 보장 표현 금지

## FE-012 기록

- 서버 pagination
- 상태/날짜 필터
- quality와 주요 패턴
- Empty/Error
- 상세 이동

## FE-013 품질

- 모바일/태블릿/데스크톱
- 키보드
- label
- 색상 외 상태
- 느린 네트워크
- WebSocket memory leak
- 렌더 성능
- 회귀 테스트

## 순서

```text
FE-001 → FE-002 → FE-003 → FE-004
                  └→ FE-005 → FE-006 → FE-007 → FE-008 → FE-009
                                                    └→ FE-010 → FE-011
                                                               → FE-012 → FE-013
```

## 완료 체크

- [ ] 계약 타입
- [ ] 로그인·보호 라우트
- [ ] 기기 방향 검증
- [ ] 양발 히트맵
- [ ] 6/8센서
- [ ] 구독 cleanup·재연결
- [ ] 한쪽 발 끊김
- [ ] 처리 중 폴링 종료
- [ ] 품질·패턴·추천
- [ ] 의료 진단 아님
- [ ] 기록 pagination
- [ ] 모바일 핵심 조작
- [ ] lint/test/build

## 계약 1.1 대응 (개선안 3.4, 2026-09-04)

| 항목 | 커밋 | 내용 |
|---|---|---|
| 타입 재생성 | `b830c89` | `schema.ts` 1.1.0 재생성, `types.ts` alias, SensorPoint `label`(선택·nullable) exact-key, 실시간 메시지 배열은 변경 없음(1.0 유지) |
| FE-1 | `0961a24` | `resultTerms`(최대 센서 신호·추정 압력중심·총 신호·센서 신호 비율), 패턴 없음 문구, 금칙어 회귀 테스트 |
| FE-2 | `4434762` | 히트맵 문구 교체, `sensor-share`(센서/전체합×100, 표시 전용), S01..S08 라벨 표시 |
| FE-9 | `d027d35` | 8센서 기본값 `layout-s01s08-v1`, 6센서 옵션 비활성(활성 seed 없음), 기기 카드 adcMax·배터리(`미보정`) |
| FE-4 | `d78a15c` | sampleRateHz 라디오 50/100(기본 50, `VITE_DEFAULT_SAMPLE_RATE_HZ`), 개발 모드 시뮬레이션 세션 체크박스 → `sourceType: SIMULATED` |
| FE-8 | `cef014d` | `실기기 · 50Hz` 배지, SIMULATED 안내, 수신기 업로드 상태 안내(5초 폴링), 신규 품질 플래그 라벨, 세션 ID 복사, 기록 필터 6종 |
| FE-3 | `714b41f` | 관찰 단계 배지·`발생 비율 62% (13/21 걸음)`, `observationSummary` 단계별 그룹, null이면 `관찰 단계 미제공(이전 분석)`, 센서 share 막대 |
| FE-5 | `96a1974` | `leftPeak/rightPeak`는 sessionId 변경 시에만 초기화(토큰 교체 유지), docs/06 스케일 검증 절차 |
| FE-6 | `ba6b520` | 만료 5분 전 배너·재로그인 다이얼로그·`lastSignoutReason` 안내, STOMP `TOKEN_EXPIRED` → `AUTH_EXPIRED`, AuthContext 이중 갱신 제거 |
| FE-7 | `c842617` | `npm run api:check`, `frontend/AGENTS.md` 갱신 절차, `verify-all.ps1/.sh` 프론트 단계에 연결 |
| 후속 | `c21cc13`, `e4739ff` | 추천 가이드 fixture를 rule-v1.2.0 코드로 갱신, 만진 파일 prettier 정리 |

### 검증 기록 2026-09-04

환경: Windows 11, Node 24 / npm 11, openapi-typescript 7.13.0, Vitest 3.2.7, ESLint 9 (`--max-warnings=0`).

| 상태 | 검사 | 명령 | 결과 |
|---|---|---|---|
| PASS | 생성 타입 동기화 | `npm run api:check` | `schema.ts` 재생성 후 `git diff --exit-code` 0 |
| PASS | 린트 | `npm run lint` | 0 errors, 0 warnings |
| PASS | 테스트 | `npm run test -- --run` | 20 files, 125 tests passed, 0 failed |
| PASS | 빌드 | `npm run build` | `prebuild` api:generate → `tsc -b` → `vite build` 성공, 빌드 후 `schema.ts` diff 0 |
| PASS | 결과 화면 금칙어 | `ResultContent.test.tsx`(패턴 유무·rule-v1.2.0 fixture) | 렌더 텍스트에 '최대 압력'·'CoP'·'정상' 0건 |
| PARTIAL | 포맷 | `npm run format:check` | 이 작업이 만진 파일은 모두 통과. 손대지 않은 기존 10개 파일(`eslint.config.js`, `tsconfig.json`, `AppErrorBoundary.tsx`, `FootDeviceSelector.tsx`, `Icon.tsx`, `StatusUi.tsx`, `main.tsx`, `DashboardPage.tsx`, `RecommendationPage.tsx`, `SignupPage.tsx`)는 이전부터 미포맷 상태로 남김 |
| NOT RUN | mock E2E 60초 | `scripts/e2e_gateway_mock.py` | MySQL·백엔드·수신기 CLI 필요. 프론트 검증 범위 밖 |

### 계획과 달라진 점

- `CreateMeasurementSessionRequest.sourceType`은 계약상 생략 가능하지만 openapi-typescript가 `default`가 있는 속성을 필수로 생성하므로 프론트는 항상 `DEVICE` 또는 `SIMULATED`를 명시해 보낸다(계약 허용 값).
- 기록 화면 패턴 필터는 계획의 9개가 아니라 계약(`PatternResult` 설명, `ObservationSummaryItem.code` enum)이 나열한 rule-v1.2.0 6종만 제공한다. 폐기 코드(`LOW_DATA_QUALITY`, `HIGH_MIDFOOT_LOAD`, `SHORT_CONTACT_TIME`)는 이전 기록 표시용 라벨(`(이전 분석)`)로만 남긴다.
- `scripts/e2e_smoke.py`의 `default_layout_version`은 백엔드 작업에서 이미 `layout-s01s08-v1`로 바뀌어 있어 프론트 작업에서는 수정하지 않았다.
- FE-6의 "만료 순간 AUTH_EXPIRED 배지"는 계획대로 제외했다(ProtectedRoute·purgeUserState 구조). 서버 ERROR 프레임 기반 매핑과 만료 전 배너·재로그인으로 대체한다.
- 결과 화면의 센서 share 막대는 레이아웃 라벨(S01..)을 알 수 없어 `#index+1`(레이아웃 index 순)으로 표시한다.
