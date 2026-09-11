import { Component, type ReactNode } from 'react';
import { Icon } from '../components/Icon';

interface Props {
  children: ReactNode;
}
interface State {
  hasError: boolean;
}

// StatePanel(role=status)과 같은 .state-panel 마크업을 쓰되, 치명 오류이므로 role=alert와 h1을 유지한다.
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
          <section className="state-panel state-panel--danger" role="alert">
            <span aria-hidden="true" className="state-panel__icon">
              <Icon name="alert" />
            </span>
            <div className="state-panel__body">
              <h1>화면을 표시하지 못했어요</h1>
              <p>페이지를 새로고침해 주세요. 문제가 계속되면 잠시 후 다시 시도해 주세요.</p>
              <div className="state-panel__action">
                <button className="button" onClick={() => window.location.reload()} type="button">
                  새로고침
                </button>
              </div>
            </div>
          </section>
        </main>
      );
    }
    return this.props.children;
  }
}
