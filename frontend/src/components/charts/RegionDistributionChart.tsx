import { formatPercent } from '../../utils/format';
import { resultTerms } from '../../utils/labels';
import { footLabels, maxOf, shareOfMax, UNAVAILABLE_TEXT, type FootKey } from './chartShared';

export interface RegionDistributionRow {
  label: string;
  /** 0..1 비율. 이전 분석 결과는 null. */
  left: number | null;
  right: number | null;
}

export interface RegionDistributionChartProps {
  rows: readonly RegionDistributionRow[];
  ariaLabel?: string;
}

const sides: readonly { key: FootKey; label: string }[] = [
  { key: 'left', label: footLabels.left },
  { key: 'right', label: footLabels.right },
];

// 부위별 좌우 그룹 가로 막대. 막대 길이는 표에 있는 가장 큰 비율을 100%로 둔 상대 길이이며
// 실제 비율은 항상 텍스트로 함께 적는다(색상만으로 좌우를 구분하지 않음).
export function RegionDistributionChart({ rows, ariaLabel }: RegionDistributionChartProps) {
  const max = maxOf(rows.flatMap((row) => [row.left, row.right]));
  return (
    <div
      aria-label={ariaLabel ?? `부위별 ${resultTerms.signalShare}`}
      className="region-chart"
      role="group"
    >
      <ul aria-hidden="true" className="chart-legend">
        {sides.map((side) => (
          <li className="chart-legend__item" key={side.key}>
            <span className={`chart-legend__swatch chart-legend__swatch--${side.key}`} />
            <span className="chart-legend__name">{side.label}</span>
          </li>
        ))}
      </ul>
      <ol className="region-chart__rows">
        {rows.map((row) => (
          <li className="region-chart__row" key={row.label}>
            <span className="region-chart__label">{row.label}</span>
            <div className="region-chart__bars">
              {sides.map((side) => {
                const value = row[side.key];
                const width = shareOfMax(value, max) * 100;
                return (
                  <div
                    className={`region-chart__bar region-chart__bar--${side.key}`}
                    key={side.key}
                  >
                    <span className="region-chart__side">{side.label}</span>
                    <span aria-hidden="true" className="region-chart__track">
                      <span className="region-chart__fill" style={{ width: `${width}%` }} />
                    </span>
                    <span
                      className={`region-chart__value${value === null ? ' region-chart__value--empty' : ''}`}
                    >
                      {value === null ? UNAVAILABLE_TEXT : formatPercent(value)}
                    </span>
                  </div>
                );
              })}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}
