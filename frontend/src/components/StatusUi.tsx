import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Icon, type IconName } from './Icon';
// 페이지 공통 partial(기록·인솔·인증·설정·도움말·상태 UI). global.css로 합치면 이 import 한 줄만 지운다.

export function Spinner({ label = '불러오는 중' }: { label?: string }) {
  return (
    <span className="spinner-wrap" role="status">
      <span aria-hidden="true" className="spinner" />
      <span>{label}</span>
    </span>
  );
}

export type StatePanelTone = 'info' | 'danger';

interface StatePanelProps {
  title: string;
  description: string;
  icon?: IconName;
  action?: ReactNode;
  compact?: boolean;
  /** 아이콘 웰 색. 오류 안내는 danger, 그 외(빈 상태·안내)는 info. */
  tone?: StatePanelTone;
}

export function StatePanel({
  title,
  description,
  icon = 'alert',
  action,
  compact = false,
  tone = 'info',
}: StatePanelProps) {
  return (
    <section
      className={`state-panel state-panel--${tone}${compact ? ' state-panel--compact' : ''}`}
      role="status"
    >
      <span aria-hidden="true" className="state-panel__icon">
        <Icon name={icon} />
      </span>
      <div className="state-panel__body">
        <h2>{title}</h2>
        <p>{description}</p>
        {action ? <div className="state-panel__action">{action}</div> : null}
      </div>
    </section>
  );
}

export function ErrorPanel({ error, retry }: { error: unknown; retry?: () => void }) {
  const description =
    error instanceof Error
      ? error.message
      : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
  return (
    <StatePanel
      title="정보를 불러오지 못했어요"
      description={description}
      tone="danger"
      action={
        retry ? (
          <button className="button button--secondary" onClick={retry} type="button">
            다시 시도
          </button>
        ) : undefined
      }
    />
  );
}

export function NotFoundPage() {
  return (
    <main className="standalone-page">
      <StatePanel
        icon="activity"
        title="페이지를 찾을 수 없어요"
        description="주소를 다시 확인하거나 대시보드로 돌아가 주세요."
        action={
          <Link className="button" to="/dashboard">
            대시보드로
          </Link>
        }
      />
    </main>
  );
}

export function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h1>{title}</h1>
        {description ? <p>{description}</p> : null}
      </div>
      {action ? <div className="page-header__action">{action}</div> : null}
    </header>
  );
}

export type StatusBadgeTone = 'positive' | 'warning' | 'danger' | 'neutral' | 'info';

// 톤은 global.css의 .status-badge--{tone} 5종(positive/warning/danger/neutral/info)과 1:1이며
// 색만으로 의미를 전달하지 않도록 항상 텍스트를 함께 렌더링한다.
export function StatusBadge({ tone, children }: { tone: StatusBadgeTone; children: ReactNode }) {
  return <span className={`status-badge status-badge--${tone}`}>{children}</span>;
}
