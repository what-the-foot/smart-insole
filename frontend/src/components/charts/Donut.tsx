import { formatPercent } from '../../utils/format';
import { clampRatio, footLabels, roundCoord, UNAVAILABLE_TEXT } from './chartShared';

export interface DonutProps {
  /** 왼발 비율(%). 두 값의 합이 100이 아니어도 합 기준으로 정규화한다. */
  leftPct: number;
  rightPct: number;
  /** 링 가운데 텍스트(예: '52 : 48'). 실제 텍스트로 렌더링되어 보조기기가 읽을 수 있다. */
  centerLabel: string;
  /** SVG 요약 문구(예: '왼발 52%, 오른발 48%'). */
  ariaLabel: string;
  /** 픽셀 크기. 기본 140. */
  size?: number;
}

// stroke-dasharray 두 개로 그리는 좌우 도넛. pathLength=100이라 dash 값이 곧 백분율이다.
export function Donut({ leftPct, rightPct, centerLabel, ariaLabel, size = 140 }: DonutProps) {
  const total = leftPct + rightPct;
  const hasData = Number.isFinite(total) && total > 0;
  const leftShare = hasData ? clampRatio(leftPct / total) : 0;
  const rightShare = hasData ? clampRatio(rightPct / total) : 0;
  const leftLen = roundCoord(leftShare * 100);
  const rightLen = roundCoord(rightShare * 100);
  const legend = [
    { key: 'left', label: footLabels.left, share: leftShare },
    { key: 'right', label: footLabels.right, share: rightShare },
  ] as const;

  return (
    <div className={`donut${hasData ? '' : ' donut--empty'}`}>
      <div className="donut__figure" style={{ width: size, height: size }}>
        <svg
          aria-label={ariaLabel}
          className="donut__svg"
          height={size}
          role="img"
          viewBox="0 0 120 120"
          width={size}
        >
          <circle className="donut__track" cx="60" cy="60" r="50" />
          <g transform="rotate(-90 60 60)">
            <circle
              className="donut__arc donut__arc--left"
              cx="60"
              cy="60"
              pathLength="100"
              r="50"
              strokeDasharray={`${leftLen} ${roundCoord(100 - leftLen)}`}
              strokeDashoffset="0"
            />
            <circle
              className="donut__arc donut__arc--right"
              cx="60"
              cy="60"
              pathLength="100"
              r="50"
              strokeDasharray={`${rightLen} ${roundCoord(100 - rightLen)}`}
              strokeDashoffset={`${-leftLen}`}
            />
          </g>
        </svg>
        <span className="donut__center">{centerLabel}</span>
      </div>
      <ul className="chart-legend donut__legend">
        {legend.map((item) => (
          <li className="chart-legend__item" key={item.key}>
            <span
              aria-hidden="true"
              className={`chart-legend__swatch chart-legend__swatch--${item.key}`}
            />
            <span className="chart-legend__name">{item.label}</span>
            <span className="chart-legend__value">
              {hasData ? formatPercent(item.share) : UNAVAILABLE_TEXT}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
