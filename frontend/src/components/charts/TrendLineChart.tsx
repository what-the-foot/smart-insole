import { formatNumber } from '../../utils/format';
import {
  buildSegments,
  extentOf,
  indexToX,
  padExtent,
  pointsAttr,
  UNAVAILABLE_TEXT,
  valueToY,
} from './chartShared';

export interface TrendPoint {
  /** x축 라벨(예: 날짜). */
  label: string;
  /** 값이 없으면 null: 선을 끊고 표에는 nullText를 적는다. */
  value: number | null;
}

export interface TrendLineChartProps {
  points: readonly TrendPoint[];
  /** 값의 단위(예: '점', '지수'). 표 머리와 캡션에 쓴다. */
  unit: string;
  /** 그림 전체를 대신하는 요약 문장. role=img의 이름이 된다. */
  ariaSummary: string;
  /** sr-only 표의 머리글. 기본 '측정' / '값'. */
  labelHeader?: string;
  valueHeader?: string;
  /** null 값의 표 문구. 기본 '제공 안 됨'. */
  nullText?: string;
  /** 값이 하나도 없을 때 문구. */
  emptyText?: string;
}

const WIDTH = 360;
const HEIGHT = 180;
const PAD = { top: 14, right: 16, bottom: 30, left: 48 } as const;
const INNER_WIDTH = WIDTH - PAD.left - PAD.right;
const INNER_HEIGHT = HEIGHT - PAD.top - PAD.bottom;
const MAX_X_LABELS = 6;

// 인라인 SVG 꺾은선. null은 빈 구간으로 남기고(보간하지 않음) 점·x 라벨·sr-only 표를 함께 그린다.
// 추세에 대한 해석 문구는 넣지 않으며 값과 단위만 표시한다.
export function TrendLineChart({
  points,
  unit,
  ariaSummary,
  labelHeader = '측정',
  valueHeader = '값',
  nullText = UNAVAILABLE_TEXT,
  emptyText = '표시할 값이 없습니다.',
}: TrendLineChartProps) {
  const values = points.map((point) => point.value);
  const rawExtent = extentOf(values);
  const extent = rawExtent ? padExtent(rawExtent) : null;
  const count = points.length;
  const toX = (index: number) => indexToX(index, count, PAD.left, INNER_WIDTH);
  const toY = (value: number) =>
    extent ? valueToY(value, extent, PAD.top, INNER_HEIGHT) : PAD.top + INNER_HEIGHT / 2;
  const segments = buildSegments(values, toX, toY);
  const dots = points.flatMap((point, index) =>
    point.value === null
      ? []
      : [{ x: toX(index), y: toY(point.value), key: `${point.label}-${index}` }],
  );
  const ticks = rawExtent
    ? [rawExtent.min, (rawExtent.min + rawExtent.max) / 2, rawExtent.max]
    : [];
  const labelStep = Math.max(1, Math.ceil(count / MAX_X_LABELS));
  const hasData = dots.length > 0;

  return (
    <figure className="trend-chart">
      <div aria-label={ariaSummary} className="trend-chart__figure" role="img">
        {hasData ? (
          <svg
            aria-hidden="true"
            className="trend-chart__svg"
            focusable="false"
            preserveAspectRatio="xMidYMid meet"
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          >
            {ticks.map((tick) => {
              const y = toY(tick);
              return (
                <g className="trend-chart__tick" key={tick}>
                  <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y} y2={y} />
                  <text dy="0.32em" textAnchor="end" x={PAD.left - 8} y={y}>
                    {formatNumber(tick)}
                  </text>
                </g>
              );
            })}
            {segments.map((segment, index) =>
              segment.length > 1 ? (
                <polyline className="trend-chart__line" key={index} points={pointsAttr(segment)} />
              ) : null,
            )}
            {dots.map((dot) => (
              <circle className="trend-chart__dot" cx={dot.x} cy={dot.y} key={dot.key} r="4" />
            ))}
            {points.map((point, index) =>
              index % labelStep === 0 || index === count - 1 ? (
                <text
                  className={`trend-chart__x-label${point.value === null ? ' trend-chart__x-label--gap' : ''}`}
                  key={`${point.label}-${index}`}
                  textAnchor="middle"
                  x={toX(index)}
                  y={HEIGHT - 8}
                >
                  {point.label}
                </text>
              ) : null,
            )}
          </svg>
        ) : (
          <p className="trend-chart__empty">{emptyText}</p>
        )}
      </div>
      <figcaption className="trend-chart__caption">단위: {unit}</figcaption>
      <table className="sr-only">
        <caption>
          {labelHeader}별 {valueHeader} ({unit})
        </caption>
        <thead>
          <tr>
            <th scope="col">{labelHeader}</th>
            <th scope="col">
              {valueHeader} ({unit})
            </th>
          </tr>
        </thead>
        <tbody>
          {points.map((point, index) => (
            <tr key={`${point.label}-${index}`}>
              <th scope="row">{point.label}</th>
              <td>{point.value === null ? nullText : formatNumber(point.value)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
