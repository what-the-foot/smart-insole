import { useId } from 'react';
import type { MovementFootSummary, MovementSummary } from '../api/types';
import { formatNumber, formatPercent } from '../utils/format';
import {
  MOVEMENT_DISCLAIMER,
  MOVEMENT_FOOT_UNAVAILABLE,
  MOVEMENT_UNAVAILABLE,
  movementTerms,
  REFERENCE_METHOD_UNAVAILABLE,
  referenceMethodLabels,
} from '../utils/labels';
import { footLabels, UNAVAILABLE_TEXT } from './charts/chartShared';
import { Icon } from './Icon';
import { StatusBadge } from './StatusUi';

export interface MovementCardProps {
  /** AnalysisResultResponse.movementSummary. rule-v1.4.0 이전 결과·IMU 프레임 없는 세션은 null. */
  summary: MovementSummary | null | undefined;
  /** 대시보드 등 좁은 영역용. eyebrow와 부호 안내를 생략하고 여백을 줄인다. */
  compact?: boolean;
}

// 정강이 IMU 요약 카드. 값은 백엔드 숫자를 그대로 표시하며 참고 범위·판정 문구를 만들지 않는다(DEC-036).
export function MovementCard({ summary, compact = false }: MovementCardProps) {
  const titleId = useId();
  const populated = summary ?? null;
  return (
    <section
      aria-labelledby={titleId}
      className={`content-card movement-card${compact ? ' movement-card--compact' : ''}`}
    >
      <div className="section-heading">
        <div>
          {compact ? null : <p className="eyebrow">MOVEMENT</p>}
          <h2 id={titleId}>{movementTerms.cardTitle}</h2>
        </div>
        <StatusBadge tone="neutral">{movementTerms.badge}</StatusBadge>
      </div>

      {populated ? (
        <>
          <dl className="movement-card__meta">
            <div>
              <dt>{movementTerms.imuCoverage}</dt>
              <dd>{formatPercent(populated.imuCoverage)}</dd>
            </div>
            <div>
              <dt>{movementTerms.referenceMethod}</dt>
              <dd>
                {populated.referenceMethod
                  ? referenceMethodLabels[populated.referenceMethod]
                  : REFERENCE_METHOD_UNAVAILABLE}
              </dd>
            </div>
          </dl>
          <div className="movement-card__feet">
            <MovementFoot foot={populated.left} side="left" />
            <MovementFoot foot={populated.right} side="right" />
          </div>
          {compact ? null : (
            <p className="metric-note movement-card__note">{movementTerms.frontalTiltSign}</p>
          )}
          <p className="movement-card__disclaimer">
            <Icon name="shield" />
            <span>{MOVEMENT_DISCLAIMER}</span>
          </p>
        </>
      ) : (
        <p className="movement-card__unavailable">
          <Icon name="alert" />
          <span>{MOVEMENT_UNAVAILABLE}</span>
        </p>
      )}
    </section>
  );
}

function MovementFoot({
  foot,
  side,
}: {
  foot: MovementFootSummary | null;
  side: 'left' | 'right';
}) {
  return (
    <article className={`movement-foot movement-foot--${side}`}>
      <div className="movement-foot__title">
        <span aria-hidden="true" className={`movement-foot__chip movement-foot__chip--${side}`}>
          {side === 'left' ? 'L' : 'R'}
        </span>
        <h3>{footLabels[side]}</h3>
      </div>
      {foot ? (
        <dl className="movement-foot__metrics">
          <MetricRow label={movementTerms.frontalTilt} unit="°" value={foot.frontalTiltDeg} />
          <MetricRow label={movementTerms.sagittalRange} unit="°" value={foot.sagittalRangeDeg} />
          <MetricRow
            label={movementTerms.transverseRange}
            unit="°"
            value={foot.transverseRangeDeg}
          />
          <MetricRow
            label={movementTerms.swingPeakAngularVelocity}
            unit="°/s"
            value={foot.swingPeakAngularVelocityDps}
          />
          <dt className="movement-foot__windows">{movementTerms.windowCount}</dt>
          <dd className="movement-foot__windows">
            {formatNumber(foot.windowCount, 0)}
            <span className="movement-foot__unit">회</span>
          </dd>
        </dl>
      ) : (
        <div className="movement-foot__unavailable">
          <strong>{UNAVAILABLE_TEXT}</strong>
          <p>{MOVEMENT_FOOT_UNAVAILABLE}</p>
        </div>
      )}
    </article>
  );
}

function MetricRow({ label, unit, value }: { label: string; unit: string; value: number | null }) {
  return (
    <>
      <dt>{label}</dt>
      {value === null ? (
        <dd className="movement-foot__value--unavailable">{UNAVAILABLE_TEXT}</dd>
      ) : (
        <dd>
          {formatNumber(value)}
          <span className="movement-foot__unit">{unit}</span>
        </dd>
      )}
    </>
  );
}
