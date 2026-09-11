import type { ReactNode } from 'react';
import { StatusBadge } from '../StatusUi';
import { formatNumber } from '../../utils/format';
import type { BadgeTone } from './chartShared';

export type KpiTone = 'primary' | 'left' | 'right' | 'positive' | 'warning' | 'danger' | 'neutral';

export interface KpiBadge {
  tone: BadgeTone;
  text: string;
}

export interface KpiCardProps {
  icon: ReactNode;
  label: string;
  /** 숫자는 formatNumber로, 문자열은 그대로 표시한다. */
  value: string | number;
  unit?: string;
  sub?: string;
  /** 배지 문구는 호출자가 백엔드 라벨(품질 단계·관찰 단계·심각도 등)로 넘긴다. */
  badge?: KpiBadge;
  tone?: KpiTone;
}

export function KpiCard({ icon, label, value, unit, sub, badge, tone = 'primary' }: KpiCardProps) {
  const valueText = typeof value === 'number' ? formatNumber(value) : value;
  return (
    <div className={`kpi-card kpi-card--${tone}`}>
      <div className="kpi-card__head">
        <span aria-hidden="true" className="kpi-card__icon">
          {icon}
        </span>
        {badge ? <StatusBadge tone={badge.tone}>{badge.text}</StatusBadge> : null}
      </div>
      <p className="kpi-card__label">{label}</p>
      <p className="kpi-card__value">
        <strong>{valueText}</strong>
        {unit ? <span className="kpi-card__unit">{unit}</span> : null}
      </p>
      {sub ? <p className="kpi-card__sub">{sub}</p> : null}
    </div>
  );
}
