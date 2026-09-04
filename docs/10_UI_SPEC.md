# 10. UI 명세

## 목표

- 현재 측정 상태를 한눈에 이해
- 양발을 같은 중요도로 표시
- 진단 화면이 아닌 재활·운동 보조 대시보드
- 수치보다 요약과 다음 행동 우선
- 모바일에서도 시작·종료 접근

## 라우트

```text
/login
/signup
/dashboard
/devices
/measurements/new
/measurements/:sessionId/live
/measurements/:sessionId/result
/history
/recommendations/:code
```

## 로그인

- 이메일·비밀번호
- 로그인
- 회원가입 이동
- 로딩·오류
- 내부 오류·토큰 미노출

## 대시보드

1. 사용자·기기 상태
2. 새 측정 시작
3. 최근 결과 요약
4. 최근 5개 기록
5. 기기·품질 안내

## 기기

- 표시명·serial
- LEFT/RIGHT
- 센서 수
- firmware
- 마지막 연결
- 상태
- 활성 보정

## 측정 준비

```text
LEFT 선택 → RIGHT 선택 → 메모 선택 → 준비 확인 → 시작
```

검증:
- 같은 기기 금지
- 방향 반대 금지
- 보정 없음 안내
- 연결 불안정 안내

## 실시간 화면

```text
┌─────────────────────────────────────────────┐
│ 측정 중 00:42  연결: 양호  품질: 좋음       │
├────────────────────┬────────────────────────┤
│ 왼발 히트맵         │ 오른발 히트맵          │
│ 총 신호·접촉·추정 압력중심 │ 총 신호·접촉·추정 압력중심 │
│ 센서별 share(%)     │ 센서별 share(%)        │
├────────────────────┴────────────────────────┤
│ 좌우 비율·안내                              │
├─────────────────────────────────────────────┤
│ [취소]                         [측정 종료]   │
└─────────────────────────────────────────────┘
```

필수 컴포넌트:
- MeasurementHeader
- ConnectionStatusBadge
- DataQualityBadge
- FootPressureHeatmap × 2
- PressureSummary
- ContactStateIndicator
- MeasurementControls
- RealtimeNotice

한쪽 발 중단:
- 연결된 발은 계속 표시
- 중단 발은 마지막 값 흐림 + 텍스트 경고
- 계속/종료 안내

재연결:
- “연결 복구 중”
- snapshot
- 구독 재개
- 자동 측정 종료 금지

## 히트맵

```ts
type SensorPoint = {
  index: number;
  x: number;
  y: number;
  region: 'HEEL' | 'MIDFOOT' | 'FOREFOOT' | 'TOE';
  medialLateral: 'MEDIAL' | 'CENTER' | 'LATERAL';
};
```

- 발 윤곽 안에 좌표 배치
- 초기 버전은 SVG 원형 gradient 가능
- 0~100 범례
- 6/8센서
- 원본 100Hz 직접 렌더 금지
- LEFT/RIGHT 미러는 layout 정책에 따름

## 결과 화면 순서

1. 요약
2. 데이터 품질
3. 좌우 균형
4. 부위별 분포
5. 접촉 시간·보행 지표
6. 패턴
7. 운동
8. 의료 진단 아님

패턴 카드:

```text
좌우 접촉 시간 차이
수준: 주의
근거: 유효 걸음 중 설정 기준 이상의 차이가 반복
안내: 같은 환경에서 재측정하고 반복 여부를 확인하세요.
```

프론트가 근거를 임의 생성하지 않습니다.

## 운동

- 이름·목적
- 시작 자세
- 순서
- 횟수·시간
- 주의
- 통증 시 중단
- 관련 패턴

“치료된다”, “교정이 보장된다” 금지.

## 기록

- 날짜 범위
- 상태·품질·패턴 필터
- 날짜, 측정 시간, 상태, 품질, 주요 패턴
- 서버 pagination

## 공통 상태

- Loading
- Empty
- Error
- Unauthorized
- Forbidden
- Processing
- Completed

PROCESSING 폴링은 완료·실패 시 중단합니다.

## 반응형

모바일:
- 양발 세로 배치 가능
- 종료 버튼 하단 접근
- 상태·히트맵 우선

태블릿:
- 양발 가로
- 지표 2열

데스크톱:
- 양발과 비교 지표 한 화면
