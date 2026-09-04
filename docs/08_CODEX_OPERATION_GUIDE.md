# 08. Codex 운영 가이드

## 왜 문서를 나누는가

Codex가 안정적으로 작업하려면 다음이 필요합니다.

- 저장소 구조
- 실행·테스트 명령
- 개발 규칙과 금지사항
- 현재 한 작업의 범위
- 완료 기준

루트 `AGENTS.md`는 항상 지켜야 할 규칙, 하위 `AGENTS.md`는 영역별 규칙, `docs/`는 상세 설계, `prompts/`는 한 번의 작업 지시를 담당합니다.

OpenAI 공식 참고:

- [Codex best practices](https://developers.openai.com/codex/learn/best-practices)
- [AGENTS.md](https://developers.openai.com/codex/agent-configuration/agents-md)
- [Codex CLI](https://developers.openai.com/codex/cli)
- [Subagents](https://developers.openai.com/codex/agent-configuration/subagents)

## 지침 계층

```text
AGENTS.md
├── 공통 도메인·보안·완료 기준
├── backend/AGENTS.md
└── frontend/AGENTS.md
```

Codex CLI에서 로드 확인 예:

```bash
codex --ask-for-approval never "현재 저장소에서 로드한 지침의 핵심을 요약하고 수정은 하지 마세요."
```

설치된 버전의 도움말이 다르면 실제 버전을 우선합니다.

## 첫 세션

`prompts/00_SESSION_START.md`를 사용합니다.

이 단계는 수정 없이:

1. 저장소 탐색
2. 문서와 코드 비교
3. 완료된 작업 판단
4. 불일치·위험 보고
5. 다음 작업 한 개 제안

## 표준 작업 흐름

1. 프롬프트 선택
2. 현재 상태와 수정 계획
3. 작업 ID 하나 구현
4. 테스트·lint·build
5. diff 자체 리뷰
6. 사람이 검토
7. 작업 보드 체크
8. 다음 ID

## 좋은 프롬프트 구조

```text
역할
작업 ID
읽을 문서
목표
수정 범위
필수 동작
금지
테스트
완료 조건
보고 형식
```

## 기존 저장소

- build/package 파일부터 읽습니다.
- 프로젝트를 재생성하지 않습니다.
- 기존 버전·패턴을 우선합니다.
- 이미 구현된 기능은 검증합니다.
- 문서와 충돌하면 차이를 먼저 보고합니다.

## 병렬 작업

계약 확정 후:

```text
Agent A + worktree A: backend/
Agent B + worktree B: frontend/
```

규칙:

1. 계약은 먼저 확정
2. 서로 다른 브랜치/worktree
3. 각자 자기 영역만 수정
4. 마지막에 INT-* 통합
5. 같은 OpenAPI 파일 동시 수정 금지

## Subagent

읽기·리뷰 중심에 적합:

- 보안
- 테스트 누락
- 계약 불일치
- DB·성능
- 접근성

동시 write는 충돌 위험이 있습니다.

리뷰 예:

```text
현재 브랜치를 병렬 subagent로 리뷰하세요.
한 명은 인증·소유권, 한 명은 멱등성과 DB,
한 명은 WebSocket lifecycle, 한 명은 테스트·접근성을 담당합니다.
모든 결과를 기다린 뒤 파일 위치와 심각도별로 통합하세요.
코드는 수정하지 마세요.
```

## Cloud 환경

필요:

- Java 21
- Node.js와 package manager
- Gradle wrapper 권한
- 의존성 설치
- 테스트 환경변수
- DB 테스트 조건

예:

```bash
chmod +x backend/gradlew || true
cd frontend && npm ci
```

Docker/DB를 사용할 수 없다면 실행하지 못한 테스트를 성공으로 간주하지 않습니다. 운영 secret을 불필요하게 제공하지 않습니다.

## 잘못된 방향 교정 문구

범위 초과:

```text
현재 작업 ID 밖의 변경을 제거하고 직접 관련된 최소 변경만 남기세요.
추가 문제는 구현하지 말고 목록으로 보고하세요.
```

계약 무시:

```text
contracts를 다시 읽고 구현을 계약에 맞추세요.
계약 변경이 필요하면 코드보다 변경안과 영향부터 제시하세요.
```

테스트 약화:

```text
삭제·약화한 테스트를 복원하세요.
요구 충돌은 우회하지 말고 보고하세요.
```

## 사람 리뷰 체크

- [ ] 작업 ID 하나인가
- [ ] 계약 일치
- [ ] 실제 테스트 결과
- [ ] 테스트 약화 없음
- [ ] secret 없음
- [ ] 타인 접근 차단
- [ ] 진단 문구 없음
- [ ] 100Hz 누적 없음
- [ ] WebSocket 실패와 DB 분리
- [ ] migration 일치

반복되는 안정된 작업은 추후 Codex Skill로 만들 수 있습니다. 현재 `prompts/`는 이해하기 쉬운 복사형 지시서로 유지합니다.
