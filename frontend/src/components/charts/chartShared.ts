import type { ObservationLevel, PatternSeverity } from '../../api/types';

// 차트 공통 상수·계산. 컴포넌트 파일에서 상수/함수를 함께 export 하면 react-refresh lint가 막으므로
// 여기(.ts)에 둔다. 값은 백엔드가 준 숫자를 그대로 그리며 프론트에서 임계값·판정을 만들지 않는다.

export type BadgeTone = 'positive' | 'warning' | 'danger' | 'neutral' | 'info';

export const footLabels = { left: '왼발', right: '오른발' } as const;

export type FootKey = keyof typeof footLabels;

export const UNAVAILABLE_TEXT = '제공 안 됨';

// ResultContent.tsx의 severityLabel/severityTone/observationTone과 같은 매핑을 유지한다.
// PatternSeverity는 백엔드 enum 표기일 뿐 건강 상태 판정이 아니다.
export const severityLabels: Record<PatternSeverity, string> = {
  INFO: '참고',
  CAUTION: '주의',
  RECHECK: '재확인',
};

export const severityTone = (severity: PatternSeverity): BadgeTone => {
  if (severity === 'INFO') return 'info';
  if (severity === 'CAUTION') return 'warning';
  return 'danger';
};

export const observationTone = (level: ObservationLevel): BadgeTone => {
  if (level === 'REPEATEDLY_OBSERVED') return 'warning';
  if (level === 'PARTIALLY_OBSERVED') return 'info';
  return 'neutral';
};

const isFiniteNumber = (value: number | null | undefined): value is number =>
  value !== null && value !== undefined && Number.isFinite(value);

export const clampRatio = (value: number): number => Math.min(1, Math.max(0, value));

/** null·비정상값·0 이하 최대값은 0으로 그린다(빈 막대). */
export const shareOfMax = (value: number | null, max: number): number => {
  if (!isFiniteNumber(value) || max <= 0) return 0;
  return clampRatio(value / max);
};

/** 유한한 값 중 최대. 값이 없거나 모두 0 이하이면 0. */
export const maxOf = (values: readonly (number | null)[]): number =>
  values.reduce<number>((acc, value) => (isFiniteNumber(value) && value > acc ? value : acc), 0);

export const finiteValues = (values: readonly (number | null)[]): number[] =>
  values.filter(isFiniteNumber);

export interface ValueExtent {
  min: number;
  max: number;
}

/** 유한한 값의 최소·최대. 값이 하나뿐이거나 모두 같으면 ±1 범위를 만들어 0으로 나누지 않게 한다. */
export const extentOf = (values: readonly (number | null)[]): ValueExtent | null => {
  const finite = finiteValues(values);
  if (finite.length === 0) return null;
  const min = Math.min(...finite);
  const max = Math.max(...finite);
  if (min === max) return { min: min - 1, max: max + 1 };
  return { min, max };
};

/** 선이 위아래 끝에 붙지 않도록 범위에 여유(비율)를 더한다. */
export const padExtent = (extent: ValueExtent, ratio = 0.1): ValueExtent => {
  const pad = (extent.max - extent.min) * ratio;
  return { min: extent.min - pad, max: extent.max + pad };
};

export interface ChartPoint {
  x: number;
  y: number;
}

/** null을 만나면 선을 끊는다. 연속 구간마다 좌표 배열을 반환한다(값 없음은 빈 구간으로 표시). */
export const buildSegments = (
  values: readonly (number | null)[],
  toX: (index: number) => number,
  toY: (value: number) => number,
): ChartPoint[][] => {
  const segments: ChartPoint[][] = [];
  let current: ChartPoint[] = [];
  values.forEach((value, index) => {
    if (!isFiniteNumber(value)) {
      if (current.length > 0) segments.push(current);
      current = [];
      return;
    }
    current.push({ x: toX(index), y: toY(value) });
  });
  if (current.length > 0) segments.push(current);
  return segments;
};

export const roundCoord = (value: number): number => Math.round(value * 100) / 100;

export const pointsAttr = (points: readonly ChartPoint[]): string =>
  points.map((point) => `${roundCoord(point.x)},${roundCoord(point.y)}`).join(' ');

/** 0..1 비율 두 개를 x 위치/y 위치로 옮기는 선형 스케일. count가 1이면 가운데에 둔다. */
export const indexToX = (index: number, count: number, start: number, width: number): number =>
  count > 1 ? start + (index / (count - 1)) * width : start + width / 2;

export const valueToY = (
  value: number,
  extent: ValueExtent,
  top: number,
  height: number,
): number => {
  const span = extent.max - extent.min;
  if (span <= 0) return top + height / 2;
  return top + height - ((value - extent.min) / span) * height;
};
