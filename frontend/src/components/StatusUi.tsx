import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Icon, type IconName } from './Icon';

export function Spinner({ label = '불러오는 중' }: { label?: string }) {
  return (
    <span className="spinner-wrap" role="status">
      <span aria-hidden="true" className="spinner" />
      <span>{label}</span>
    </span>
  );
}

interface StatePanelProps {
  title: string;
  description: string;
  icon?: IconName;
  action?: ReactNode;
  compact?: boolean;
}

export function StatePanel({
  title,
  description,
  icon = 'alert',
  action,
  compact = false,
}: StatePanelProps) {
  return (
    <section className={`state-panel${compact ? ' state-panel--compact' : ''}`} role="status">
      <span className="state-panel__icon"><Icon name={icon} /></span>
      <div>
        <h2>{title}</h2>
        <p>{description}</p>
        {action ? <div className="state-panel__action">{action}</div> : null}
      </div>
    </section>
  );
}

export function ErrorPanel({ error, retry }: { error: unknown; retry?: () => void }) {
  const description =
    error instanceof Error ? error.message : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
  return (
    <StatePanel
      title="정보를 불러오지 못했어요"
      description={description}
      action={retry ? <button className="button button--secondary" onClick={retry}>다시 시도</button> : undefined}
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
        action={<Link className="button" to="/dashboard">대시보드로</Link>}
      />
    </main>
  );
}

export function PageHeader({ eyebrow, title, description, action }: {
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

export function StatusBadge({ tone, children }: {
  tone: 'positive' | 'warning' | 'danger' | 'neutral' | 'info';
  children: ReactNode;
}) {
  return <span className={`status-badge status-badge--${tone}`}>{children}</span>;
}
