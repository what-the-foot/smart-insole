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
