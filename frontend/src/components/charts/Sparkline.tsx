import { formatNumber } from '../../utils/format';
import {
  buildSegments,
  extentOf,
  finiteValues,
  indexToX,
  padExtent,
  pointsAttr,
  roundCoord,
  valueToY,
} from './chartShared';

export interface SparklineProps {
  /** 시간순 값. null은 빈 구간. */
  values: readonly (number | null)[];
  /** 기본값: '값 N개의 변화 흐름, 최근 X'. */
  ariaLabel?: string;
  width?: number;
  height?: number;
}

const PAD = 3;

// 작은 SVG 꺾은선. 축·해석 문구 없이 값의 흐름만 보여 주며 마지막 값에 점을 찍는다.
export function Sparkline({ values, ariaLabel, width = 96, height = 28 }: SparklineProps) {
  const finite = finiteValues(values);
  const rawExtent = extentOf(values);
  const extent = rawExtent ? padExtent(rawExtent) : null;
  const count = values.length;
  const toX = (index: number) => indexToX(index, count, PAD, width - PAD * 2);
  const toY = (value: number) =>
    extent ? valueToY(value, extent, PAD, height - PAD * 2) : height / 2;
  const segments = buildSegments(values, toX, toY);
  const lastIndex = values.reduce<number>(
    (acc, value, index) => (value !== null && Number.isFinite(value) ? index : acc),
    -1,
  );
  const lastValue = lastIndex >= 0 ? values[lastIndex] : null;
  const hasData = finite.length > 0 && lastValue !== null && lastValue !== undefined;
  const label =
    ariaLabel ??
    (hasData
      ? `값 ${finite.length}개의 변화 흐름, 최근 ${formatNumber(lastValue)}`
      : '표시할 값이 없습니다.');

  return (
    <svg
      aria-label={label}
      className={`sparkline${hasData ? '' : ' sparkline--empty'}`}
      height={height}
      role="img"
      viewBox={`0 0 ${width} ${height}`}
      width={width}
    >
      {segments.map((segment, index) => {
        const single = segment[0];
        if (segment.length === 1 && single) {
          return (
            <circle
              className="sparkline__point"
              cx={roundCoord(single.x)}
              cy={roundCoord(single.y)}
              key={index}
              r="2"
            />
          );
        }
        return <polyline className="sparkline__line" key={index} points={pointsAttr(segment)} />;
      })}
      {hasData ? (
        <circle
          className="sparkline__dot"
          cx={roundCoord(toX(lastIndex))}
          cy={roundCoord(toY(lastValue))}
          r="2.5"
        />
      ) : null}
    </svg>
  );
}
