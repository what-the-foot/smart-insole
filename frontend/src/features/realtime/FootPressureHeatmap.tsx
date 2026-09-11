import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { FootRealtimeData, FootSide, SensorLayoutResponse } from '../../api/types';
import { formatNumber } from '../../utils/format';
import { contactStateLabels, resultTerms } from '../../utils/labels';
import type { LayoutStatus } from './layoutStatus';
import { Icon } from '../../components/Icon';
import { usePreferences } from '../../app/preferences';
import { heatGradientCss, pressureColor } from './heatmapColors';
import {
  FALLBACK_COLUMNS,
  FALLBACK_ROWS,
  FOOT_OUTLINE_PATH,
  LEFT_MIRROR_TRANSFORM,
  RASTER_HEIGHT,
  RASTER_WIDTH,
  VIEWBOX_HEIGHT,
  VIEWBOX_WIDTH,
  computeField,
  heatmapX,
  heatmapY,
  renderFieldToDataUrl,
  supportsRaster,
  type FieldSample,
} from './heatmapField';
import {
  LEGACY_SHARE_MESSAGE,
  sensorSharePercent,
  sensorShareMax,
  sensorTotal,
  topSensors,
} from './sensorShare';

type PresentFootData = NonNullable<FootRealtimeData>;
type CopPoint = NonNullable<PresentFootData['cop']>;
type HeatmapMode = 'realtime' | 'share';

const sideLabel = (side: FootSide): string => (side === 'LEFT' ? '왼발' : '오른발');
const sensorName = (label: string | null | undefined, index: number): string =>
  label ?? `#${index + 1}`;
const safeId = (id: string): string => id.replace(/[^a-zA-Z0-9_-]/g, '');
/** 설정 '애니메이션 줄이기'가 켜지면 표면 재도색을 이 간격(≈4fps) 이하로만 한다. */
export const REDUCED_MOTION_THROTTLE_MS = 250;
const LAYOUT_LOADING_TEXT = '센서 배치를 불러오는 중입니다.';
const LAYOUT_UNAVAILABLE_TEXT = '센서 배치를 확인할 수 없어 센서 위치를 표시하지 않습니다.';

/** layout이 없을 때의 안내. 실제 조회 중일 때만 로딩 문구를 쓰고 오류·기기 없음은 흐린 안내로 구분한다. */
function LayoutOverlay({ status }: { status: LayoutStatus }) {
  const loading = status === 'loading';
  return (
    <p className={`heatmap-overlay${loading ? '' : ' heatmap-overlay--muted'}`}>
      <Icon name="alert" />
      {loading ? LAYOUT_LOADING_TEXT : LAYOUT_UNAVAILABLE_TEXT}
    </p>
  );
}

interface RasterInput {
  readonly samples: readonly FieldSample[];
  readonly side: FootSide;
}

/** 래스터 표면 PNG data URL. realtime은 throttleMs(≤12fps) 간격으로만 다시 칠한다. */
function useRasterDataUrl(input: RasterInput | null, throttleMs: number): string | null {
  const [url, setUrl] = useState<string | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const lastPaintRef = useRef(0);

  useEffect(() => {
    if (!input || !supportsRaster()) {
      setUrl(null);
      return;
    }
    const paint = () => {
      canvasRef.current ??= document.createElement('canvas');
      lastPaintRef.current = Date.now();
      const field = computeField(input.samples, input.side, RASTER_WIDTH, RASTER_HEIGHT);
      setUrl(renderFieldToDataUrl(canvasRef.current, field, RASTER_WIDTH, RASTER_HEIGHT));
    };
    const wait = throttleMs - (Date.now() - lastPaintRef.current);
    if (wait <= 0) {
      paint();
      return;
    }
    const timer = window.setTimeout(paint, wait);
    return () => window.clearTimeout(timer);
  }, [input, throttleMs]);

  return url;
}

export function FootHeatmapSvg({
  side,
  layout,
  colorValues,
  valueText,
  shareText,
  cop,
  title,
  describedBy,
  throttleMs,
}: {
  side: FootSide;
  layout: SensorLayoutResponse | undefined;
  /** 레이아웃 index 순 0..100 색상 값. null이면 표면을 그리지 않고 원만 남긴다. */
  colorValues: readonly number[] | null;
  /** 원 안에 표시할 텍스트(색이 아닌 채널). null이면 생략. */
  valueText: (index: number) => string | null;
  /** 원 아래 %에 표시할 텍스트. null이면 생략. */
  shareText: (index: number) => string | null;
  cop: CopPoint | null;
  title: string;
  describedBy: string;
  throttleMs: number;
}) {
  const uid = safeId(useId());
  const clipId = `foot-clip-${uid}`;
  const blurId = `heat-blur-${uid}`;
  const mirror = side === 'LEFT' ? LEFT_MIRROR_TRANSFORM : undefined;
  // 설정 페이지(preferences.ts): '센서 점' 모드는 보간 표면 없이 원·수치만, '애니메이션 줄이기'는 재도색 빈도를 낮춘다.
  const [{ heatmapMode, reduceMotion }] = usePreferences();
  const pointsOnly = heatmapMode === 'points';
  const effectiveThrottleMs = reduceMotion
    ? Math.max(throttleMs, REDUCED_MOTION_THROTTLE_MS)
    : throttleMs;

  const rasterInput = useMemo<RasterInput | null>(() => {
    if (!layout || !colorValues || pointsOnly) return null;
    const samples = layout.points.map((point) => ({
      x: point.x,
      y: point.y,
      value: colorValues[point.index] ?? 0,
    }));
    return { samples, side };
  }, [layout, colorValues, side, pointsOnly]);

  const rasterUrl = useRasterDataUrl(rasterInput, effectiveThrottleMs);
  const useFallback = !supportsRaster();
  const fallbackField = useMemo(
    () =>
      useFallback && rasterInput
        ? computeField(rasterInput.samples, rasterInput.side, FALLBACK_COLUMNS, FALLBACK_ROWS)
        : null,
    [useFallback, rasterInput],
  );
  const cellWidth = VIEWBOX_WIDTH / FALLBACK_COLUMNS;
  const cellHeight = VIEWBOX_HEIGHT / FALLBACK_ROWS;

  return (
    <svg
      aria-describedby={describedBy}
      role="img"
      viewBox={`0 0 ${VIEWBOX_WIDTH} ${VIEWBOX_HEIGHT}`}
    >
      <title>{title}</title>
      <defs>
        <clipPath id={clipId}>
          <path d={FOOT_OUTLINE_PATH} transform={mirror} />
        </clipPath>
        <filter id={blurId}>
          <feGaussianBlur stdDeviation="3" />
        </filter>
      </defs>
      <path className="foot-outline" d={FOOT_OUTLINE_PATH} transform={mirror} />
      {rasterUrl ? (
        <image
          className="heatmap-surface"
          clipPath={`url(#${clipId})`}
          height={VIEWBOX_HEIGHT}
          href={rasterUrl}
          preserveAspectRatio="none"
          style={{ imageRendering: 'auto' }}
          width={VIEWBOX_WIDTH}
          x={0}
          y={0}
        />
      ) : null}
      {fallbackField ? (
        <g className="heatmap-surface heatmap-surface--cells" clipPath={`url(#${clipId})`}>
          <g filter={`url(#${blurId})`}>
            {Array.from(fallbackField, (value, i) => (
              <rect
                className="heat-cell"
                fill={pressureColor(value)}
                height={cellHeight + 0.5}
                key={i}
                width={cellWidth + 0.5}
                x={(i % FALLBACK_COLUMNS) * cellWidth}
                y={Math.floor(i / FALLBACK_COLUMNS) * cellHeight}
              />
            ))}
          </g>
        </g>
      ) : null}
      {layout?.points.map((point) => {
        const value = colorValues?.[point.index] ?? 0;
        const x = heatmapX(point.x, side);
        const y = heatmapY(point.y);
        const text = valueText(point.index);
        const share = shareText(point.index);
        return (
          <g key={point.index}>
            <circle
              className={`sensor-point${colorValues ? '' : ' sensor-point--empty'}`}
              cx={x}
              cy={y}
              fill={pressureColor(value)}
              r={18}
            />
            {text === null ? null : (
              <text className="sensor-value" textAnchor="middle" x={x} y={y + 4}>
                {text}
              </text>
            )}
            {share === null ? null : (
              <text className="sensor-share" textAnchor="middle" x={x} y={y + 30}>
                {share}
              </text>
            )}
            {point.label ? (
              <text className="sensor-label" textAnchor="middle" x={x} y={y - 22}>
                {point.label}
              </text>
            ) : null}
          </g>
        );
      })}
      {cop ? (
        <g
          className="cop-marker"
          transform={`translate(${heatmapX(cop.x, side)} ${heatmapY(cop.y)})`}
        >
          <circle r="8" />
          <path d="M-13 0h26M0-13v26" />
        </g>
      ) : null}
    </svg>
  );
}

export function FootPressureHeatmap({
  side,
  data,
  layout,
  layoutStatus = 'loading',
  disconnected,
  throttleMs = 80,
}: {
  side: FootSide;
  data: PresentFootData | null;
  layout: SensorLayoutResponse | undefined;
  /** layout이 undefined인 이유. 기본값은 조회 중. */
  layoutStatus?: LayoutStatus | undefined;
  disconnected: boolean;
  /** 표면 재도색 최소 간격(ms). 기본 80ms ≈ 12fps. */
  throttleMs?: number;
}) {
  const uid = safeId(useId());
  const titleId = `heatmap-title-${uid}`;
  const summaryId = `heatmap-summary-${uid}`;
  const label = sideLabel(side);
  const values = data?.sensorValues ?? null;
  const total = values ? sensorTotal(values) : 0;
  const topText =
    values && layout
      ? topSensors(values)
          .map((entry) => {
            const point = layout.points.find((candidate) => candidate.index === entry.index);
            return `${sensorName(point?.label, entry.index)} ${Math.round(entry.value)}`;
          })
          .join(', ')
      : '';
  const sensorSummary = data
    ? `${data.sensorValues.length}개 센서, 상대 ${resultTerms.totalSignal} ${formatNumber(data.totalPressure)}, ${contactStateLabels[data.contactState]}.${topText ? ` 가장 높은 신호: ${topText}.` : ''}`
    : '아직 센서 데이터가 없습니다.';

  return (
    <figure
      className={`heatmap-card heatmap-card--realtime${disconnected ? ' heatmap-card--disconnected' : ''}`}
      aria-labelledby={titleId}
    >
      <div className="heatmap-card__header">
        <div>
          <span className={`device-side device-side--${side.toLowerCase()}`}>
            {side === 'LEFT' ? 'L' : 'R'}
          </span>
          <div>
            <p className="eyebrow">{side}</p>
            <h2 id={titleId}>{label} 센서 신호</h2>
          </div>
        </div>
        <span className={`foot-connection${disconnected ? ' foot-connection--off' : ''}`}>
          <span aria-hidden="true" />
          {disconnected ? '데이터 끊김' : data ? '수신 중' : '대기 중'}
        </span>
      </div>
      <div className="heatmap-visual">
        <FootHeatmapSvg
          colorValues={values}
          cop={data?.cop ?? null}
          describedBy={summaryId}
          layout={layout}
          shareText={(index) => {
            if (!values) return null;
            const share = sensorSharePercent(values[index] ?? 0, total);
            return share === null ? null : `${share}%`;
          }}
          side={side}
          throttleMs={throttleMs}
          title={`${label} 센서 신호 히트맵`}
          valueText={(index) => (values ? `${Math.round(values[index] ?? 0)}` : null)}
        />
        {!layout ? <LayoutOverlay status={layoutStatus} /> : null}
        {disconnected ? (
          <p className="heatmap-overlay heatmap-overlay--warning">
            <Icon name="alert" />
            {data ? '마지막 수신값을 흐리게 표시합니다.' : `${label} 데이터가 아직 없습니다.`}
          </p>
        ) : null}
      </div>
      <figcaption id={summaryId} className="heatmap-summary">
        <div>
          <span>상대 {resultTerms.totalSignal}</span>
          <strong>{data ? formatNumber(data.totalPressure) : '—'}</strong>
        </div>
        <div>
          <span>접촉 상태</span>
          <strong>{data ? contactStateLabels[data.contactState] : '대기'}</strong>
        </div>
        <div>
          <span>{resultTerms.estimatedCop}</span>
          <strong>
            {data?.cop
              ? `${formatNumber(data.cop.x, 2)}, ${formatNumber(data.cop.y, 2)}`
              : '감지 안 됨'}
          </strong>
        </div>
        <p className="sr-only">{sensorSummary}</p>
      </figcaption>
    </figure>
  );
}

/**
 * 세션 평균 센서 신호 비율(leftSensorSharePct/rightSensorSharePct) 히트맵.
 * 색은 호출자가 넘긴 공통 최대값(shareMax, 보통 sensorShareMax(left, right)) 기준이라 양발을 같은 축으로 비교한다.
 * meanCoP를 넘기지 않으면(undefined) 압력중심 행을 그리지 않는다(ResultContent의 고정 텍스트 수와 충돌 방지).
 */
export function SessionShareHeatmap({
  side,
  layout,
  layoutStatus = 'loading',
  sharePct,
  shareMax,
  meanCoP,
  headingLevel = 'h3',
}: {
  side: FootSide;
  layout: SensorLayoutResponse | undefined;
  /** layout이 undefined인 이유. 기본값은 조회 중. */
  layoutStatus?: LayoutStatus | undefined;
  sharePct: readonly number[] | null;
  shareMax?: number;
  meanCoP?: CopPoint | null;
  headingLevel?: 'h2' | 'h3';
}) {
  const uid = safeId(useId());
  const titleId = `share-heatmap-title-${uid}`;
  const summaryId = `share-heatmap-summary-${uid}`;
  const label = sideLabel(side);
  const max = shareMax ?? sensorShareMax(sharePct);
  const colorValues = useMemo(
    () => (sharePct ? sharePct.map((share) => (max > 0 ? (share / max) * 100 : 0)) : null),
    [sharePct, max],
  );
  const entryText = (index: number, value: number): string =>
    `${sensorName(layout?.points.find((point) => point.index === index)?.label, index)} ${Math.round(value)}%`;
  const top = sharePct ? topSensors(sharePct, 1)[0] : undefined;
  const summary = sharePct
    ? `${resultTerms.signalShare}: ${sharePct.map((share, index) => entryText(index, share)).join(', ')}.`
    : LEGACY_SHARE_MESSAGE;
  const Heading = headingLevel;

  return (
    <figure className="heatmap-card heatmap-card--share" aria-labelledby={titleId}>
      <div className="heatmap-card__header">
        <div>
          <span className={`device-side device-side--${side.toLowerCase()}`}>
            {side === 'LEFT' ? 'L' : 'R'}
          </span>
          <div>
            <p className="eyebrow">{side}</p>
            <Heading id={titleId}>
              {label} {resultTerms.signalShare}
            </Heading>
          </div>
        </div>
      </div>
      <div className="heatmap-visual">
        <FootHeatmapSvg
          colorValues={colorValues}
          cop={meanCoP ?? null}
          describedBy={summaryId}
          layout={layout}
          shareText={() => null}
          side={side}
          throttleMs={0}
          title={`${label} ${resultTerms.signalShare} 히트맵`}
          valueText={(index) => {
            const share = sharePct?.[index];
            return share === undefined ? null : `${Math.round(share)}%`;
          }}
        />
        {!layout ? <LayoutOverlay status={layoutStatus} /> : null}
        {sharePct ? null : (
          <p className="heatmap-overlay heatmap-overlay--muted">
            <Icon name="alert" />
            {LEGACY_SHARE_MESSAGE}
          </p>
        )}
      </div>
      <figcaption id={summaryId} className="heatmap-summary">
        <div>
          <span>가장 높은 {resultTerms.signalShare}</span>
          <strong>{top ? entryText(top.index, top.value) : '—'}</strong>
        </div>
        <div>
          <span>비율 합계</span>
          <strong>{sharePct ? '100%' : '—'}</strong>
        </div>
        {meanCoP === undefined ? null : (
          <div>
            <span>평균 {resultTerms.estimatedCop}</span>
            <strong>
              {meanCoP ? `x ${formatNumber(meanCoP.x, 2)} · y ${formatNumber(meanCoP.y, 2)}` : '—'}
            </strong>
          </div>
        )}
        <p className="sr-only">{summary}</p>
      </figcaption>
    </figure>
  );
}

export function PressureLegend({
  orientation = 'horizontal',
  mode = 'realtime',
  title,
}: {
  orientation?: 'horizontal' | 'vertical';
  mode?: HeatmapMode;
  title?: string;
}) {
  const heading =
    title ?? (mode === 'share' ? `${resultTerms.signalShare} (%)` : '상대 신호 (0–100)');
  const note =
    mode === 'share'
      ? '세션 접촉 프레임 평균 · 합계 100%'
      : `0–100 상대값 · 원 아래 %는 ${resultTerms.signalShare}`;
  const low = <span className="pressure-legend__label pressure-legend__label--low">낮음</span>;
  const high = <span className="pressure-legend__label pressure-legend__label--high">높음</span>;
  return (
    <div
      aria-label={
        mode === 'share'
          ? `${resultTerms.signalShare} 범례: 낮음에서 높음`
          : '상대 신호 범례: 0은 낮음, 100은 높음'
      }
      className={`pressure-legend pressure-legend--${orientation} pressure-legend--${mode}`}
      role="group"
    >
      <p className="pressure-legend__title">{heading}</p>
      {orientation === 'vertical' ? high : low}
      <div
        aria-hidden="true"
        className="pressure-legend__bar"
        style={{ background: heatGradientCss(orientation) }}
      />
      {orientation === 'vertical' ? low : high}
      <strong className="pressure-legend__note">{note}</strong>
    </div>
  );
}
