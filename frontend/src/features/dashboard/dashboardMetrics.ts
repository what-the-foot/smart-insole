import type {
  MeasurementHistoryItem,
  MeasurementStatus,
  PatternResult,
  PressureDistribution,
  QualityLevel,
} from '../../api/types';
import type { MeasurementListParams } from '../../api/services';
import type { RegionDistributionRow } from '../../components/charts/RegionDistributionChart';
import type { TrendPoint } from '../../components/charts/TrendLineChart';
import type { BadgeTone } from '../../components/charts/chartShared';
import { UNAVAILABLE_TEXT } from '../../components/charts/chartShared';
import type { IconName } from '../../components/Icon';
import { formatNumber } from '../../utils/format';
import { OBSERVATION_LEVEL_UNAVAILABLE } from '../../utils/labels';

// 대시보드 표시 계산(순수 함수). 값은 백엔드 숫자를 그대로 쓰고 임계값·판정 문구를 만들지 않는다.
// 방향 표현은 '증가'/'감소'/'변화 없음'만 사용한다(점수의 오르내림이며 상태 판정이 아니다).

export const HISTORY_WINDOW_DAYS = 7;
export const HISTORY_PAGE_SIZE = 10;
const DAY_MS = 86_400_000;

/** 최근 7일 창의 시작 시각(ISO-8601 UTC). 테스트가 결정적이도록 기준 시각을 넘겨받는다. */
export const historyWindowFrom = (now: Date): string =>
  new Date(now.getTime() - HISTORY_WINDOW_DAYS * DAY_MS).toISOString();

export const historyParams = (
  now: Date,
): MeasurementListParams & { status: MeasurementStatus; from: string } => ({
  page: 0,
  size: HISTORY_PAGE_SIZE,
  status: 'COMPLETED',
  from: historyWindowFrom(now),
});

/** 좌우 신호 비율 '52 : 48'. 어느 한쪽이 null이면 제공 안 됨. */
export const loadShareText = (
  left: number | null | undefined,
  right: number | null | undefined,
): string =>
  left === null || left === undefined || right === null || right === undefined
    ? UNAVAILABLE_TEXT
    : `${formatNumber(left, 0)} : ${formatNumber(right, 0)}`;

export interface PatternLevelCounts {
  repeated: number;
  partial: number;
  /** 관찰 단계가 없는 이전 분석 패턴 수. */
  unavailable: number;
}

export const countPatternLevels = (patterns: readonly PatternResult[]): PatternLevelCounts =>
  patterns.reduce<PatternLevelCounts>(
    (acc, pattern) => {
      const level = pattern.observationLevel ?? null;
      if (level === 'REPEATEDLY_OBSERVED') acc.repeated += 1;
      else if (level === 'PARTIALLY_OBSERVED') acc.partial += 1;
      else if (level === null) acc.unavailable += 1;
      return acc;
    },
    { repeated: 0, partial: 0, unavailable: 0 },
  );

/** '반복 관찰 n · 일부 관찰 m'. 모든 패턴이 이전 분석(단계 없음)이면 미제공 안내. */
export const patternCountSub = (counts: PatternLevelCounts): string =>
  counts.unavailable > 0 && counts.repeated === 0 && counts.partial === 0
    ? OBSERVATION_LEVEL_UNAVAILABLE
    : `반복 관찰 ${counts.repeated} · 일부 관찰 ${counts.partial}`;

export type QualityDirection = 'UP' | 'DOWN' | 'FLAT' | 'NONE';

export interface QualityComparison {
  direction: QualityDirection;
  /** 최신 점수 − 창 안 다른 세션 평균(소수 1자리 반올림). 비교 대상이 없으면 null. */
  delta: number | null;
  /** 비교에 쓴 다른 세션 수. */
  count: number;
}

const round1 = (value: number): number => Math.round(value * 10) / 10;

/** 최신 점수를 최근 창 안의 다른 완료 세션 품질 점수 평균과 비교한다(자기 자신 제외). */
export const compareQuality = (
  latestScore: number,
  otherScores: readonly (number | null | undefined)[],
): QualityComparison => {
  const finite = otherScores.filter(
    (score): score is number => typeof score === 'number' && Number.isFinite(score),
  );
  if (finite.length === 0) return { direction: 'NONE', delta: null, count: 0 };
  const mean = finite.reduce((sum, score) => sum + score, 0) / finite.length;
  const delta = round1(latestScore - mean);
  const direction: QualityDirection = delta > 0 ? 'UP' : delta < 0 ? 'DOWN' : 'FLAT';
  return { direction, delta, count: finite.length };
};

export const qualityComparisonText = (comparison: QualityComparison): string => {
  if (comparison.direction === 'NONE' || comparison.delta === null)
    return `최근 ${HISTORY_WINDOW_DAYS}일에 비교할 다른 측정이 없습니다.`;
  if (comparison.direction === 'FLAT') return `최근 ${HISTORY_WINDOW_DAYS}일 평균과 변화 없음`;
  const sign = comparison.direction === 'UP' ? '+' : '-';
  const word = comparison.direction === 'UP' ? '증가' : '감소';
  return `최근 ${HISTORY_WINDOW_DAYS}일 평균 대비 ${word} (${sign}${formatNumber(Math.abs(comparison.delta))}점)`;
};

/** 최근 창의 다른 세션 품질 점수(최신 세션 제외). */
export const otherQualityScores = (
  items: readonly MeasurementHistoryItem[],
  latestSessionId: string,
): (number | null | undefined)[] =>
  items.filter((item) => item.sessionId !== latestSessionId).map((item) => item.dataQualityScore);

/** 품질 단계 배지·카드 톤. KpiTone과 BadgeTone 양쪽에 속하는 값만 쓴다. */
export type QualityTone = Extract<BadgeTone, 'positive' | 'warning' | 'danger'>;

export const qualityTone = (level: QualityLevel): QualityTone =>
  level === 'GOOD' ? 'positive' : level === 'POOR' ? 'danger' : 'warning';

// ResultContent와 같은 부위 라벨. 후족부는 항상 있고 중족부·전족부는 이전 분석에서 null이다.
export const regionRows = (distribution: PressureDistribution): RegionDistributionRow[] => [
  { label: '후족부', left: distribution.leftHeelRatio, right: distribution.rightHeelRatio },
  {
    label: '중족부',
    left: distribution.leftMidfootRatio,
    right: distribution.rightMidfootRatio,
  },
  {
    label: '전족부·발가락',
    left: distribution.leftForefootRatio,
    right: distribution.rightForefootRatio,
  },
];

export const trendMetricKeys = [
  'symmetryIndex',
  'qualityScore',
  'leftLoadSharePct',
  'meanStrideTimeMs',
] as const;

export type TrendMetricKey = (typeof trendMetricKeys)[number];

export interface TrendMetric {
  label: string;
  unit: string;
  pick: (item: MeasurementHistoryItem) => number | null;
}

export const trendMetrics: Record<TrendMetricKey, TrendMetric> = {
  symmetryIndex: {
    label: '좌우 대칭 지수',
    unit: '지수',
    pick: (item) => item.symmetryIndex ?? null,
  },
  qualityScore: {
    label: '품질 점수',
    unit: '점',
    pick: (item) => item.dataQualityScore ?? null,
  },
  leftLoadSharePct: {
    label: '좌우 신호 비율(왼발 %)',
    unit: '%',
    pick: (item) => item.leftLoadSharePct ?? null,
  },
  meanStrideTimeMs: {
    label: '스트라이드 시간(추정)',
    unit: '밀리초',
    pick: (item) => item.meanStrideTimeMs ?? null,
  },
};

export const isTrendMetricKey = (value: string): value is TrendMetricKey =>
  (trendMetricKeys as readonly string[]).includes(value);

/** 목록 API는 최신순이므로 차트용으로 오래된 순으로 정렬한다. */
export const sortChronologically = (
  items: readonly MeasurementHistoryItem[],
): MeasurementHistoryItem[] =>
  [...items].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));

/** x축 라벨 'M/D'(브라우저 로컬 날짜). */
export const shortDateLabel = (iso: string): string => {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '날짜 없음';
  return `${date.getMonth() + 1}/${date.getDate()}`;
};

export const trendPoints = (
  items: readonly MeasurementHistoryItem[],
  key: TrendMetricKey,
): TrendPoint[] =>
  sortChronologically(items).map((item) => ({
    label: shortDateLabel(item.createdAt),
    value: trendMetrics[key].pick(item),
  }));

/** 스파크라인용 값 배열(오래된 순). */
export const strideSeries = (items: readonly MeasurementHistoryItem[]): (number | null)[] =>
  sortChronologically(items).map((item) => item.meanStrideTimeMs ?? null);

const patternIcons: Record<string, IconName> = {
  MEDIAL_LOAD_TENDENCY: 'medial',
  LATERAL_LOAD_TENDENCY: 'lateral',
  LEFT_RIGHT_ASYMMETRY: 'asymmetry',
  LOW_HALLUX_SIGNAL: 'hallux',
  FOREFOOT_LOAD_TENDENCY: 'forefoot',
  REARFOOT_LOAD_TENDENCY: 'rearfoot',
};

export const patternIconName = (code: string): IconName => patternIcons[code] ?? 'foot';
