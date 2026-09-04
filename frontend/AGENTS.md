# Frontend AGENTS.md

루트 `AGENTS.md`와 함께 적용한다.

## 책임

- 로그인과 인증 상태
- 기기 선택과 측정 제어
- 양발 실시간 히트맵
- 연결 상태, 데이터 품질, 경과 시간
- 분석 결과, 운동 가이드, 기록
- 오류와 재연결 안내

분석 임계값이나 질환 판정 로직을 프론트에 구현하지 않는다.

## 구조

```text
src/
├── app/
├── api/
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

기존 구조가 일관되면 기존 방식을 우선한다.

## TypeScript

- 새 코드에 `any` 금지
- API DTO와 화면 상태 분리
- 공통 타입은 OpenAPI 생성 또는 중앙 타입으로 관리
- 응답 타입 중복 선언 금지
- `as unknown as`로 계약 오류를 숨기지 않음
- 서버 enum 전체를 명시

## 상태 관리

- 서버 데이터는 TanStack Query
- 폼·단일 화면 UI는 로컬 상태 우선
- 전역 저장소는 여러 화면이 공유하는 최소 상태에만 사용
- WebSocket 최신값을 매번 서버 캐시 전체에 넣어 과도한 렌더를 만들지 않음
- 100Hz 전체 history를 브라우저에 무제한 누적하지 않음

## API

- Axios, 인증 헤더, 오류 변환은 `src/api/`에서 중앙 관리
- 컴포넌트에서 URL 직접 조합 금지
- Loading/Empty/Error/Unauthorized/Forbidden 처리
- 계약 변경 시 타입 생성·검증 실행

## WebSocket

- STOMP 연결·구독·해제·재연결을 별도 훅/서비스로 관리
- 소유 세션만 구독
- 연결 끊김과 데이터 미수신을 구분
- 재연결 후 REST snapshot을 불러오고 재구독
- unmount 시 구독·타이머 정리
- 10~20Hz 화면 갱신을 목표로 불필요한 렌더 최소화

## 히트맵

- 왼발·오른발 동시 표시
- sensor layout 좌표 사용
- 범례 표시
- 한쪽 발 끊김 시 다른 발 유지
- quality가 낮으면 경고
- 색상만으로 상태 전달 금지
- 6/8센서 지원

## 문구

- 백엔드 패턴 문구를 기본 표시
- 프론트에서 질환명 임의 추가 금지
- “본 결과는 의료 진단이 아닙니다” 안내
- 오류는 사용자가 다음 행동을 알 수 있게 작성

## 접근성·반응형

- 버튼·폼에 접근 가능한 이름
- 키보드 사용 가능
- 모바일에서 측정 종료 버튼 접근 가능
- 차트·히트맵에 요약 텍스트
- 색상 외 텍스트·아이콘 제공

## 테스트

- API 훅 성공·로딩·오류
- 측정 시작·종료 상태
- WebSocket Mock과 cleanup
- 양발 매핑, 한쪽 발 끊김
- 진단형 표현 회귀 방지
- 사용자 행동 중심 테스트

## 검증

```bash
npm run lint
npm run test -- --run
npm run build
```

## Frontend Review Rules

- API URL·타입이 중복되는가
- 토큰·개인정보가 콘솔에 출력되는가
- 구독이 중복되거나 해제되지 않는가
- 100Hz를 그대로 렌더하는가
- LEFT/RIGHT가 뒤바뀌는가
- 실패인데 측정 중으로 오해하게 하는가
- 모바일에서 종료 버튼이 가려지는가
- 색상만으로 경고하는가
