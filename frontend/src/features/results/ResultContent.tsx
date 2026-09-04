import { Link } from 'react-router-dom';
import type {
  AnalysisResultResponse,
  MeasurementSessionResponse,
  ObservationLevel,
  ObservationSummaryItem,
  PatternResult,
  SensorSharePct,
} from '../../api/types';
import { Icon } from '../../components/Icon';
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

export function ResultContent({
  result,
  session,
}: {
  result: AnalysisResultResponse;
  session?: SessionMeta;
}) {
  const qualityTone =
    result.dataQuality.level === 'GOOD'
      ? ('positive' as const)
      : result.dataQuality.level === 'POOR'
        ? ('danger' as const)
        : ('warning' as const);
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
          aria-label={`데이터 품질 ${result.dataQuality.score}점, ${qualityLabels[result.dataQuality.level]}`}
          style={{
            background: `conic-gradient(var(--color-primary) ${result.dataQuality.score}%, rgba(255,255,255,.2) 0)`,
          }}
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

      <section aria-labelledby="gait-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">GAIT SUMMARY</p>
            <h2 id="gait-title">보행 요약</h2>
          </div>
        </div>
        <div className="metric-grid">
          <article className="metric-card">
            <span>
              <Icon name="check" />
            </span>
            <p>분석에 사용한 유효 걸음</p>
            <strong>
              {result.gaitSummary.validStepCount === null
                ? '—'
                : formatNumber(result.gaitSummary.validStepCount, 0)}
            </strong>
            <small>{result.gaitSummary.validStepCount === null ? '이전 분석' : '회'}</small>
          </article>
          <article className="metric-card">
            <span>
              <Icon name="activity" />
            </span>
            <p>분당 걸음 수</p>
            <strong>{formatNumber(result.gaitSummary.cadence)}</strong>
            <small>걸음/분</small>
          </article>
          <article className="metric-card">
            <span className="metric-card__left">L</span>
            <p>왼발 접촉 시간</p>
            <strong>{formatNumber(result.gaitSummary.leftContactTimeMs)}</strong>
            <small>밀리초</small>
          </article>
          <article className="metric-card">
            <span className="metric-card__right">R</span>
            <p>오른발 접촉 시간</p>
            <strong>{formatNumber(result.gaitSummary.rightContactTimeMs)}</strong>
            <small>밀리초</small>
          </article>
          <article className="metric-card">
            <span>
              <Icon name="sparkles" />
            </span>
            <p>좌우 대칭 지수</p>
            <strong>{formatNumber(result.gaitSummary.symmetryIndex)}</strong>
            <small>비교 지수</small>
          </article>
        </div>
        <p className="metric-note">
          대칭 지수는 좌우 차이를 비교하기 위한 분석값입니다. 단독으로 건강 상태를 판단하지
          않습니다.
        </p>
      </section>

      <section className="content-card" aria-labelledby="distribution-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">SIGNAL DISTRIBUTION</p>
            <h2 id="distribution-title">좌우 신호 분포</h2>
          </div>
        </div>
        <div className="distribution-grid">
          <DistributionFoot
            label="왼발"
            medial={result.pressureDistribution.leftMedialRatio}
            lateral={result.pressureDistribution.leftLateralRatio}
            heel={result.pressureDistribution.leftHeelRatio}
            midfoot={result.pressureDistribution.leftMidfootRatio}
            forefoot={result.pressureDistribution.leftForefootRatio}
            peak={result.pressureDistribution.leftPeakPressure}
            meanCoP={result.pressureDistribution.leftMeanCoP}
            sharePct={result.pressureDistribution.leftSensorSharePct ?? null}
            side="left"
          />
          <DistributionFoot
            label="오른발"
            medial={result.pressureDistribution.rightMedialRatio}
            lateral={result.pressureDistribution.rightLateralRatio}
            heel={result.pressureDistribution.rightHeelRatio}
            midfoot={result.pressureDistribution.rightMidfootRatio}
            forefoot={result.pressureDistribution.rightForefootRatio}
            peak={result.pressureDistribution.rightPeakPressure}
            meanCoP={result.pressureDistribution.rightMeanCoP}
            sharePct={result.pressureDistribution.rightSensorSharePct ?? null}
            side="right"
          />
        </div>
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

function DistributionFoot({
  label,
  medial,
  lateral,
  heel,
  midfoot,
  forefoot,
  peak,
  meanCoP,
  sharePct,
  side,
}: {
  label: string;
  medial: number;
  lateral: number;
  heel: number;
  midfoot: number | null;
  forefoot: number | null;
  peak: number | null;
  meanCoP: { x: number; y: number } | null;
  sharePct: SensorSharePct | null;
  side: 'left' | 'right';
}) {
  return (
    <article className="distribution-foot">
      <div className="distribution-foot__title">
        <span className={`device-side device-side--${side}`}>{side === 'left' ? 'L' : 'R'}</span>
        <h3>{label}</h3>
      </div>
      <div className="ratio-group">
        <strong>좌우 방향</strong>
        <RatioBar label="내측" ratio={medial} />
        <RatioBar label="외측" ratio={lateral} />
      </div>
      <div className="ratio-group">
        <strong>발 길이 방향</strong>
        <RatioBar label="뒤꿈치" ratio={heel} />
        <RatioBar label="중족부" ratio={midfoot} />
        <RatioBar label="전족부·발가락" ratio={forefoot} />
      </div>
      {sharePct ? (
        <div className="ratio-group share-bars">
          <strong>{resultTerms.signalShare} (레이아웃 index 순)</strong>
          {sharePct.map((share, index) => (
            <div className="ratio-row share-bar" key={index}>
              <div>
                <span>#{index + 1}</span>
                <strong>{formatNumber(share)}%</strong>
              </div>
              <div className="ratio-track" aria-hidden="true">
                <span style={{ width: `${Math.max(0, Math.min(100, share))}%` }} />
              </div>
            </div>
          ))}
        </div>
      ) : null}
      <div className="distribution-summary">
        <div>
          <span>{resultTerms.peakSignal}</span>
          <strong>{peak === null ? '제공 안 됨' : `${formatNumber(peak)} / 100`}</strong>
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

function RatioBar({ label, ratio }: { label: string; ratio: number | null }) {
  return (
    <div className="ratio-row">
      <div>
        <span>{label}</span>
        <strong>{ratio === null ? '제공 안 됨' : formatPercent(ratio)}</strong>
      </div>
      <div className="ratio-track" aria-hidden="true">
        <span style={{ width: `${Math.max(0, Math.min(1, ratio ?? 0)) * 100}%` }} />
      </div>
    </div>
  );
}
