import type { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import type {
  AnalysisResultResponse,
  MeasurementSessionResponse,
  ObservationLevel,
  ObservationSummaryItem,
  PatternResult,
  SensorLayoutResponse,
  SensorSharePct,
} from '../../api/types';
import { Donut, RegionDistributionChart } from '../../components/charts';
import { footLabels, UNAVAILABLE_TEXT, type FootKey } from '../../components/charts/chartShared';
import { Icon } from '../../components/Icon';
import { MovementCard } from '../../components/MovementCard';
import { StatusBadge } from '../../components/StatusUi';
import { formatDateTime, formatNumber, formatPercent } from '../../utils/format';
import {
  OBSERVATION_LEVEL_UNAVAILABLE,
  observationLevelLabels,
  observationLevelOrder,
  occurrenceRateText,
  patternCodeLabel,
  qualityFlagLabel,
  qualityLabels,
  resultTerms,
  sessionSourceBadge,
} from '../../utils/labels';
import { PressureLegend, SessionShareHeatmap } from '../realtime/FootPressureHeatmap';
import type { LayoutStatus } from '../realtime/layoutStatus';
import { LEGACY_SHARE_MESSAGE, sensorShareMax } from '../realtime/sensorShare';

type SessionMeta = Pick<MeasurementSessionResponse, 'sourceType' | 'sampleRateHz'>;

const severityTone = (severity: PatternResult['severity']) =>
  severity === 'INFO'
    ? ('info' as const)
    : severity === 'CAUTION'
      ? ('warning' as const)
      : ('danger' as const);

const severityLabel: Record<PatternResult['severity'], string> = {
  INFO: '참고',
  CAUTION: '주의',
  RECHECK: '재확인',
};

const observationTone = (level: ObservationLevel) =>
  level === 'REPEATEDLY_OBSERVED'
    ? ('warning' as const)
    : level === 'PARTIALLY_OBSERVED'
      ? ('info' as const)
      : ('neutral' as const);

const groupObservationSummary = (summary: ObservationSummaryItem[]) =>
  observationLevelOrder.map((level) => ({
    level,
    items: summary.filter((item) => item.observationLevel === level),
  }));

const LOAD_SHARE_LABEL = '좌우 신호 비율';
const STRIDE_LABEL = '스트라이드 시간(추정)';

export function ResultContent({
  result,
  session,
  leftLayout,
  rightLayout,
  leftLayoutStatus,
  rightLayoutStatus,
}: {
  result: AnalysisResultResponse;
  session?: SessionMeta;
  /** 세션 왼발/오른발 기기의 센서 배치. 없으면 히트맵이 layoutStatus에 맞는 안내를 보여준다. */
  leftLayout?: SensorLayoutResponse | undefined;
  rightLayout?: SensorLayoutResponse | undefined;
  leftLayoutStatus?: LayoutStatus;
  rightLayoutStatus?: LayoutStatus;
}) {
  const qualityTone =
    result.dataQuality.level === 'GOOD'
      ? ('positive' as const)
      : result.dataQuality.level === 'POOR'
        ? ('danger' as const)
        : ('warning' as const);
  const gait = result.gaitSummary;
  const distribution = result.pressureDistribution;
  // 계약 1.3.0(rule-v1.3.0) 필드. 이전 결과는 null이며 0으로 그리지 않는다.
  const leftLoad = distribution.leftLoadSharePct ?? null;
  const rightLoad = distribution.rightLoadSharePct ?? null;
  const loadShare =
    leftLoad !== null && rightLoad !== null ? { left: leftLoad, right: rightLoad } : null;
  const leftShare = distribution.leftSensorSharePct ?? null;
  const rightShare = distribution.rightSensorSharePct ?? null;
  const hasSensorShare = leftShare !== null || rightShare !== null;
  const shareMax = sensorShareMax(leftShare, rightShare);
  const qualityRingStyle = { '--quality-pct': `${result.dataQuality.score}%` } as CSSProperties;

  return (
    <div className="result-stack">
      <section className="result-hero">
        <div>
          <p className="eyebrow eyebrow--light">MEASUREMENT COMPLETE</p>
          <h1>측정 결과를 정리했어요.</h1>
          <p>
            {formatDateTime(result.createdAt)} · 분석 버전 {result.algorithmVersion}
            {session ? ` · ${sessionSourceBadge(session)}` : ''}
          </p>
        </div>
        <div
          className="quality-ring"
          role="img"
          aria-label={`데이터 품질 ${result.dataQuality.score}점, ${qualityLabels[result.dataQuality.level]}`}
          style={qualityRingStyle}
        >
          <span>
            <strong>{result.dataQuality.score}</strong>
            <small>품질 점수</small>
          </span>
        </div>
      </section>

      {session?.sourceType === 'SIMULATED' ? (
        <p className="notice notice--info" role="status">
          <Icon name="alert" />
          시뮬레이션 세션의 결과입니다. 실기기 측정이 아니므로 보행 해석에 사용하지 마세요.
        </p>
      ) : null}

      <section className="content-card" aria-labelledby="quality-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">DATA QUALITY</p>
            <h2 id="quality-title">데이터 품질</h2>
          </div>
          <StatusBadge tone={qualityTone}>{qualityLabels[result.dataQuality.level]}</StatusBadge>
        </div>
        <div className="quality-overview">
          <div>
            <strong>{formatPercent(1 - result.dataQuality.missingFrameRate)}</strong>
            <span>유효 프레임 비율</span>
          </div>
          <p>
            {result.dataQuality.level === 'POOR'
              ? '데이터 품질이 낮아 같은 환경에서 다시 측정하는 것을 권장합니다.'
              : '분석에 사용된 데이터의 수신 상태를 함께 확인하세요.'}
          </p>
        </div>
        {result.dataQuality.flags.length ? (
          <ul className="flag-list">
            {result.dataQuality.flags.map((flag) => (
              <li key={flag}>
                <Icon name="alert" />
                {qualityFlagLabel(flag)}
              </li>
            ))}
          </ul>
        ) : (
          <p className="positive-line">
            <Icon name="check" />
            기록된 주요 품질 경고가 없습니다.
          </p>
        )}
      </section>

      <section className="gait-section" aria-labelledby="gait-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">GAIT SUMMARY</p>
            <h2 id="gait-title">보행 요약</h2>
          </div>
        </div>
        <div className="gait-layout">
          <div className="metric-grid">
            <article className="metric-card">
              <span>
                <Icon name="check" />
              </span>
              <p>분석에 사용한 유효 걸음</p>
              <strong>
                {gait.validStepCount === null ? '—' : formatNumber(gait.validStepCount, 0)}
              </strong>
              <small>{gait.validStepCount === null ? '이전 분석' : '회'}</small>
            </article>
            <article className="metric-card">
              <span>
                <Icon name="activity" />
              </span>
              <p>분당 걸음 수</p>
              <strong>{formatNumber(gait.cadence)}</strong>
              <small>걸음/분</small>
            </article>
            <article className="metric-card">
              <span className="metric-card__left">L</span>
              <p>왼발 접촉 시간</p>
              <strong>{formatNumber(gait.leftContactTimeMs)}</strong>
              <small>밀리초</small>
            </article>
            <article className="metric-card">
              <span className="metric-card__right">R</span>
              <p>오른발 접촉 시간</p>
              <strong>{formatNumber(gait.rightContactTimeMs)}</strong>
              <small>밀리초</small>
            </article>
            <article className="metric-card">
              <span>
                <Icon name="sparkles" />
              </span>
              <p>좌우 대칭 지수</p>
              <strong>{formatNumber(gait.symmetryIndex)}</strong>
              <small>비교 지수</small>
            </article>
          </div>
          <article className="content-card balance-card" aria-labelledby="balance-title">
            <div className="balance-card__head">
              <p className="eyebrow">LOAD SHARE</p>
              <h3 id="balance-title">{LOAD_SHARE_LABEL}</h3>
            </div>
            <Donut
              ariaLabel={
                loadShare
                  ? `${LOAD_SHARE_LABEL} 왼발 ${formatNumber(loadShare.left)}%, 오른발 ${formatNumber(loadShare.right)}%`
                  : `${LOAD_SHARE_LABEL} ${UNAVAILABLE_TEXT}`
              }
              centerLabel={
                loadShare
                  ? `${Math.round(loadShare.left)} : ${Math.round(loadShare.right)}`
                  : UNAVAILABLE_TEXT
              }
              leftPct={loadShare?.left ?? 0}
              rightPct={loadShare?.right ?? 0}
              size={128}
            />
            <p className="metric-note">
              양발 접촉 프레임 평균 신호합의 상대 비율(좌 : 우 %)입니다. 힘이나 체중의 비율이
              아니며, 이전 분석 결과에는 제공되지 않습니다.
            </p>
          </article>
        </div>
        <section className="content-card stride-card" aria-labelledby="stride-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">STRIDE TIME</p>
              <h3 id="stride-title">{STRIDE_LABEL}</h3>
            </div>
            <StatusBadge tone="neutral">접촉 구간 기반 추정</StatusBadge>
          </div>
          <div className="stride-tiles">
            <StrideTile label={footLabels.left} side="left" value={gait.leftStrideTimeMs ?? null} />
            <StrideTile
              label={footLabels.right}
              side="right"
              value={gait.rightStrideTimeMs ?? null}
            />
            <StrideTile label="평균" value={gait.meanStrideTimeMs ?? null} />
          </div>
          <p className="metric-note">
            같은 발의 연속 접촉 구간 시작 간격의 중앙값입니다. 접촉 구간이 2개 미만이거나 이전 분석
            결과면 제공되지 않으며, 임상 검증된 보행 주기가 아닙니다.
          </p>
        </section>
        <p className="metric-note">
          대칭 지수는 좌우 차이를 비교하기 위한 분석값입니다. 단독으로 건강 상태를 판단하지
          않습니다.
        </p>
      </section>

      <MovementCard summary={result.movementSummary} />

      <section className="content-card distribution-section" aria-labelledby="distribution-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">SIGNAL DISTRIBUTION</p>
            <h2 id="distribution-title">좌우 신호 분포</h2>
            <p>세션 접촉 프레임 평균 기준의 센서별·부위별 {resultTerms.signalShare}입니다.</p>
          </div>
        </div>
        <div
          className={`distribution-layout${hasSensorShare ? '' : ' distribution-layout--legacy'}`}
        >
          {hasSensorShare ? (
            <div className="distribution-heatmaps">
              <SessionShareHeatmap
                layout={leftLayout}
                layoutStatus={leftLayoutStatus}
                shareMax={shareMax}
                sharePct={leftShare}
                side="LEFT"
              />
              <SessionShareHeatmap
                layout={rightLayout}
                layoutStatus={rightLayoutStatus}
                shareMax={shareMax}
                sharePct={rightShare}
                side="RIGHT"
              />
              <div className="distribution-heatmaps__legend">
                <PressureLegend mode="share" orientation="vertical" />
              </div>
            </div>
          ) : (
            <p className="distribution-legacy">
              <Icon name="alert" />
              <span>{LEGACY_SHARE_MESSAGE}</span>
            </p>
          )}
          <div className="distribution-charts">
            <div className="distribution-chart">
              <h3>발 길이 방향</h3>
              <RegionDistributionChart
                ariaLabel={`발 길이 방향 ${resultTerms.signalShare}`}
                rows={[
                  {
                    label: '뒤꿈치',
                    left: distribution.leftHeelRatio,
                    right: distribution.rightHeelRatio,
                  },
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
                ]}
              />
            </div>
            <div className="distribution-chart">
              <h3>좌우 방향</h3>
              <RegionDistributionChart
                ariaLabel={`좌우 방향 ${resultTerms.signalShare}`}
                rows={[
                  {
                    label: '내측',
                    left: distribution.leftMedialRatio,
                    right: distribution.rightMedialRatio,
                  },
                  {
                    label: '외측',
                    left: distribution.leftLateralRatio,
                    right: distribution.rightLateralRatio,
                  },
                ]}
              />
            </div>
          </div>
        </div>
        <div className="foot-summary-grid">
          <FootSummary
            meanCoP={distribution.leftMeanCoP}
            peak={distribution.leftPeakPressure}
            side="left"
          />
          <FootSummary
            meanCoP={distribution.rightMeanCoP}
            peak={distribution.rightPeakPressure}
            side="right"
          />
        </div>
        {hasSensorShare ? (
          <ShareTable layout={leftLayout ?? rightLayout} left={leftShare} right={rightShare} />
        ) : null}
        <p className="metric-note">
          전족부는 발가락 영역을 포함합니다. {resultTerms.peakSignal}와 평균{' '}
          {resultTerms.estimatedCop}은 세션 ADC 기준 0~100 상대 신호와 0~1 좌표계의 분석 요약이며,
          보정된 압력값이나 의료 진단값이 아닙니다. {resultTerms.signalShare}은 접촉 프레임 평균에서
          센서/전체합×100으로 계산한 값입니다.
        </p>
      </section>

      <section aria-labelledby="pattern-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">OBSERVED PATTERNS</p>
            <h2 id="pattern-title">관찰된 패턴</h2>
          </div>
        </div>
        {result.patterns.length ? (
          <div className="pattern-list">
            {result.patterns.map((pattern) => (
              <PatternCard key={`${pattern.code}-${pattern.title}`} pattern={pattern} />
            ))}
          </div>
        ) : (
          <div className="no-pattern">
            <Icon name="check" />
            <div>
              <h3>이번 측정에서 표시할 주요 패턴이 없습니다.</h3>
              <p>
                반복 관찰된 경향이 없었다는 뜻일 뿐이며, 질환 유무나 건강 상태를 확정하는 의미가
                아닙니다.
              </p>
            </div>
          </div>
        )}
      </section>

      <section className="content-card" aria-labelledby="observation-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">OBSERVATION SUMMARY</p>
            <h2 id="observation-title">관찰 단계 요약</h2>
          </div>
        </div>
        {result.observationSummary ? (
          <div className="observation-summary">
            {groupObservationSummary(result.observationSummary).map((group) => (
              <div className="observation-group" key={group.level}>
                <div className="observation-group__title">
                  <StatusBadge tone={observationTone(group.level)}>
                    {observationLevelLabels[group.level]}
                  </StatusBadge>
                  <small>{group.items.length}종</small>
                </div>
                {group.items.length ? (
                  <ul>
                    {group.items.map((item) => (
                      <li key={item.code}>
                        <strong>{patternCodeLabel(item.code)}</strong>
                        <span>{occurrenceRateText(item)}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="observation-group__empty">해당 단계의 패턴이 없습니다.</p>
                )}
              </div>
            ))}
            <p className="metric-note">
              유효 걸음(접촉 구간)을 창으로 삼아 각 패턴이 관찰된 비율입니다. 단계 경계는 기능
              검증용 제안값이며 질환 판단이 아닙니다.
            </p>
          </div>
        ) : (
          <p className="observation-summary__legacy">
            <Icon name="alert" />
            {OBSERVATION_LEVEL_UNAVAILABLE} — 분석 버전 {result.algorithmVersion} 결과에는 관찰 단계
            정보가 없습니다.
          </p>
        )}
      </section>

      <section aria-labelledby="recommendation-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">MOVEMENT GUIDE</p>
            <h2 id="recommendation-title">추천 운동</h2>
            <p>분석 결과와 연결된 가벼운 운동 안내입니다.</p>
          </div>
        </div>
        {result.recommendations.length ? (
          <div className="recommendation-grid">
            {result.recommendations.map((recommendation) => (
              <article className="recommendation-card" key={recommendation.code}>
                <span className="recommendation-card__art">
                  <Icon name="sparkles" />
                </span>
                <div>
                  <p className="eyebrow">약 {recommendation.durationMinutes}분</p>
                  <h3>{recommendation.title}</h3>
                  <p>{recommendation.summary}</p>
                  <Link
                    className="text-link"
                    to={`/recommendations/${encodeURIComponent(recommendation.code)}`}
                  >
                    가이드 확인
                    <Icon name="arrow" />
                  </Link>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <div className="no-pattern">
            <Icon name="activity" />
            <div>
              <h3>제공된 추천 운동이 없습니다.</h3>
              <p>무리해서 임의의 운동을 시작하지 말고 현재 몸 상태를 살펴보세요.</p>
            </div>
          </div>
        )}
      </section>

      <aside className="medical-disclaimer" aria-label="의료 안내">
        <Icon name="shield" />
        <div>
          <strong>본 결과는 의료 진단이 아닙니다.</strong>
          <p>{result.disclaimer}</p>
        </div>
      </aside>
      <div className="result-actions">
        <Link className="button button--secondary" to="/history">
          측정 기록 보기
        </Link>
        <Link className="button" to="/measurements/new">
          <Icon name="plus" />
          다시 측정
        </Link>
      </div>
    </div>
  );
}

function PatternCard({ pattern }: { pattern: PatternResult }) {
  const level = pattern.observationLevel ?? null;
  const occurrence = occurrenceRateText(pattern);
  return (
    <article className="pattern-card">
      <div className="pattern-card__top">
        <span className="pattern-card__icon">
          <Icon name="activity" />
        </span>
        <div>
          <div className="pattern-card__badges">
            <StatusBadge tone={severityTone(pattern.severity)}>
              {severityLabel[pattern.severity]}
            </StatusBadge>
            {level ? (
              <StatusBadge tone={observationTone(level)}>
                {observationLevelLabels[level]}
              </StatusBadge>
            ) : null}
          </div>
          <h3>{pattern.title}</h3>
        </div>
      </div>
      <p>{pattern.message}</p>
      <p className="pattern-card__occurrence">{occurrence ?? OBSERVATION_LEVEL_UNAVAILABLE}</p>
      <div className="evidence-box">
        <strong>관찰 근거</strong>
        <p>{pattern.evidence}</p>
      </div>
    </article>
  );
}

// 스트라이드 시간 타일. null은 '제공 안 됨'으로 적고 0으로 그리지 않는다.
function StrideTile({
  label,
  side,
  value,
}: {
  label: string;
  side?: FootKey;
  value: number | null;
}) {
  return (
    <div className={`stride-tile${side ? ` stride-tile--${side}` : ''}`}>
      <span className="stride-tile__label">
        {side ? (
          <span aria-hidden="true" className={`stride-tile__chip stride-tile__chip--${side}`}>
            {side === 'left' ? 'L' : 'R'}
          </span>
        ) : null}
        {label}
      </span>
      {value === null ? (
        <strong className="stride-tile__value stride-tile__value--unavailable">
          {UNAVAILABLE_TEXT}
        </strong>
      ) : (
        <strong className="stride-tile__value">
          {formatNumber(value, 0)}
          <small>밀리초</small>
        </strong>
      )}
    </div>
  );
}

// 발별 요약(최대 센서 신호·평균 추정 압력중심). 히트맵에는 압력중심을 그리지 않고 여기서만 적는다.
function FootSummary({
  side,
  peak,
  meanCoP,
}: {
  side: FootKey;
  peak: number | null;
  meanCoP: { x: number; y: number } | null;
}) {
  return (
    <article className={`foot-summary foot-summary--${side}`}>
      <div className="foot-summary__title">
        <span aria-hidden="true" className={`device-side device-side--${side}`}>
          {side === 'left' ? 'L' : 'R'}
        </span>
        <h3>{footLabels[side]}</h3>
      </div>
      <div className="distribution-summary">
        <div>
          <span>{resultTerms.peakSignal}</span>
          <strong>{peak === null ? UNAVAILABLE_TEXT : `${formatNumber(peak)} / 100`}</strong>
        </div>
        <div>
          <span>평균 {resultTerms.estimatedCop}</span>
          <strong>
            {meanCoP
              ? `x ${formatNumber(meanCoP.x, 2)} · y ${formatNumber(meanCoP.y, 2)}`
              : '데이터 없음'}
          </strong>
        </div>
      </div>
    </article>
  );
}

const shareCell = (values: SensorSharePct | null, index: number): string => {
  const value = values?.[index];
  return value === undefined ? '—' : `${formatNumber(value)}%`;
};

// 히트맵의 센서별 비율을 표로도 제공한다(색·위치가 아닌 텍스트 채널). 행 수는 센서 수와 같다.
function ShareTable({
  left,
  right,
  layout,
}: {
  left: SensorSharePct | null;
  right: SensorSharePct | null;
  layout: SensorLayoutResponse | undefined;
}) {
  const count = Math.max(left?.length ?? 0, right?.length ?? 0);
  return (
    <details className="share-table">
      <summary>센서별 {resultTerms.signalShare} 표 보기 (레이아웃 index 순)</summary>
      <table>
        <caption className="sr-only">
          센서별 {resultTerms.signalShare}. 접촉 프레임 평균에서 센서/전체합×100.
        </caption>
        <thead>
          <tr>
            <th scope="col">센서</th>
            <th scope="col">{footLabels.left}</th>
            <th scope="col">{footLabels.right}</th>
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: count }, (_unused, index) => {
            const label = layout?.points.find((point) => point.index === index)?.label ?? null;
            return (
              <tr className="share-bar" key={index}>
                <th scope="row">
                  #{index + 1}
                  {label ? <small>{label}</small> : null}
                </th>
                <td>{shareCell(left, index)}</td>
                <td>{shareCell(right, index)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </details>
  );
}
