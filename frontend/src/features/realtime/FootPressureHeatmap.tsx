import type { FootRealtimeData, FootSide, SensorLayoutResponse } from '../../api/types';
import { formatNumber } from '../../utils/format';
import { contactStateLabels, resultTerms } from '../../utils/labels';
import { Icon } from '../../components/Icon';
import { sensorSharePercent, sensorTotal } from './sensorShare';

type PresentFootData = NonNullable<FootRealtimeData>;

const pressureColor = (value: number): string => {
  const clamped = Math.max(0, Math.min(100, value));
  const hue = 196 - clamped * 1.75;
  const lightness = 76 - clamped * 0.28;
  return `hsl(${hue} 78% ${lightness}%)`;
};

const heatmapX = (value: number, side: FootSide): number =>
  52 + (side === 'LEFT' ? 1 - value : value) * 125;

export function FootPressureHeatmap({
  side,
  data,
  layout,
  disconnected,
}: {
  side: FootSide;
  data: PresentFootData | null;
  layout: SensorLayoutResponse | undefined;
  disconnected: boolean;
}) {
  const label = side === 'LEFT' ? '왼발' : '오른발';
  const total = data ? sensorTotal(data.sensorValues) : 0;
  const sensorSummary = data
    ? `${data.sensorValues.length}개 센서, 상대 ${resultTerms.totalSignal} ${formatNumber(data.totalPressure)}, ${contactStateLabels[data.contactState]}`
    : '아직 센서 데이터가 없습니다.';

  return (
    <figure className={`heatmap-card${disconnected ? ' heatmap-card--disconnected' : ''}`} aria-labelledby={`${side}-heatmap-title`}>
      <div className="heatmap-card__header">
        <div><span className={`device-side device-side--${side.toLowerCase()}`}>{side === 'LEFT' ? 'L' : 'R'}</span><div><p className="eyebrow">{side}</p><h2 id={`${side}-heatmap-title`}>{label} 센서 신호</h2></div></div>
        <span className={`foot-connection${disconnected ? ' foot-connection--off' : ''}`}><span aria-hidden="true" />{disconnected ? '데이터 끊김' : data ? '수신 중' : '대기 중'}</span>
      </div>
      <div className="heatmap-visual">
        <svg aria-describedby={`${side}-heatmap-summary`} role="img" viewBox="0 0 220 400">
          <title>{label} 센서 신호 히트맵</title>
          <defs>
            <filter id={`glow-${side}`}><feGaussianBlur stdDeviation="11" /></filter>
          </defs>
          <path className="foot-outline" d="M109 371c-36 0-58-30-57-69 1-30 17-55 24-77 8-24 3-48 1-74-2-37 6-81 30-108 17-19 48-24 62-5 13 18 6 49-3 68-9 20-13 38-7 63 7 30 20 62 18 99-3 59-26 103-68 103Z" transform={side === 'LEFT' ? 'translate(229 0) scale(-1 1)' : undefined} />
          {layout?.points.map((point) => {
            const value = data?.sensorValues[point.index] ?? 0;
            const share = data ? sensorSharePercent(value, total) : null;
            const x = heatmapX(point.x, side);
            const y = 42 + point.y * 310;
            return (
              <g key={point.index}>
                <circle cx={x} cy={y} fill={pressureColor(value)} filter={`url(#glow-${side})`} opacity={data ? 0.68 : 0.12} r={31} />
                <circle className="sensor-point" cx={x} cy={y} fill={pressureColor(value)} r={18} />
                <text className="sensor-value" textAnchor="middle" x={x} y={y + 4}>{Math.round(value)}</text>
                {share === null ? null : <text className="sensor-share" textAnchor="middle" x={x} y={y + 30}>{share}%</text>}
                {point.label ? <text className="sensor-label" textAnchor="middle" x={x} y={y - 22}>{point.label}</text> : null}
              </g>
            );
          })}
          {data?.cop ? <g className="cop-marker" transform={`translate(${heatmapX(data.cop.x, side)} ${42 + data.cop.y * 310})`}><circle r="8" /><path d="M-13 0h26M0-13v26" /></g> : null}
        </svg>
        {!layout ? <p className="heatmap-overlay"><Icon name="alert" />센서 배치를 불러오는 중입니다.</p> : null}
        {disconnected ? <p className="heatmap-overlay heatmap-overlay--warning"><Icon name="alert" />{data ? '마지막 수신값을 흐리게 표시합니다.' : `${label} 데이터가 아직 없습니다.`}</p> : null}
      </div>
      <figcaption id={`${side}-heatmap-summary`} className="heatmap-summary">
        <div><span>상대 {resultTerms.totalSignal}</span><strong>{data ? formatNumber(data.totalPressure) : '—'}</strong></div>
        <div><span>접촉 상태</span><strong>{data ? contactStateLabels[data.contactState] : '대기'}</strong></div>
        <div><span>{resultTerms.estimatedCop}</span><strong>{data?.cop ? `${formatNumber(data.cop.x, 2)}, ${formatNumber(data.cop.y, 2)}` : '감지 안 됨'}</strong></div>
        <p className="sr-only">{sensorSummary}</p>
      </figcaption>
    </figure>
  );
}

export function PressureLegend() {
  return <div className="pressure-legend" aria-label="상대 신호 범례: 0은 낮음, 100은 높음"><span>낮음</span><div aria-hidden="true" /><span>높음</span><strong>0–100 상대값 · 원 아래 %는 {resultTerms.signalShare}</strong></div>;
}
