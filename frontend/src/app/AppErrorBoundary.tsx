import { Component, type ReactNode } from 'react';

interface Props { children: ReactNode }
interface State { hasError: boolean }

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(): void {
    // 사용자 데이터나 인증 정보를 콘솔로 전달하지 않습니다.
  }

  render() {
    if (this.state.hasError) {
      return (
        <main className="standalone-page">
          <section className="state-panel" role="alert">
            <h1>화면을 표시하지 못했어요</h1>
            <p>페이지를 새로고침해 주세요. 문제가 계속되면 잠시 후 다시 시도해 주세요.</p>
            <button className="button" onClick={() => window.location.reload()}>새로고침</button>
          </section>
        </main>
      );
    }
    return this.props.children;
  }
}
