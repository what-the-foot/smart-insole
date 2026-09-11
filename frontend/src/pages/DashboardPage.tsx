import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { useDevices, useSensorLayout } from '../api/queries';
import type {
  AnalysisResultResponse,
  DeviceResponse,
  FootSide,
  MeasurementHistoryItem,
} from '../api/types';
import {
  ContactTimeBars,
  Donut,
  KpiCard,
  PatternList,
  RecommendationTile,
  RegionDistributionChart,
  Sparkline,
  TrendLineChart,
} from '../components/charts';
import { UNAVAILABLE_TEXT } from '../components/charts/chartShared';
import { Icon } from '../components/Icon';
import { MovementCard } from '../components/MovementCard';
import { ErrorPanel, PageHeader, Spinner, StatePanel } from '../components/StatusUi';
import { useAuth } from '../features/auth/AuthContext';
import {
  compareQuality,
  countPatternLevels,
  isTrendMetricKey,
  loadShareText,
  otherQualityScores,
  patternCountSub,
  patternIconName,
  qualityComparisonText,
  qualityTone,
  regionRows,
  strideSeries,
  trendMetricKeys,
  trendMetrics,
  trendPoints,
  HISTORY_WINDOW_DAYS,
  type TrendMetricKey,
} from '../features/dashboard/dashboardMetrics';
import { useHistorySummary } from '../features/dashboard/useHistorySummary';
import { PressureLegend, SessionShareHeatmap } from '../features/realtime/FootPressureHeatmap';
import { sensorLayoutStatus, type LayoutStatus } from '../features/realtime/layoutStatus';
import { sensorShareMax } from '../features/realtime/sensorShare';
import { useLatestResult } from '../features/results/useLatestResult';
import { formatDateTime, formatNumber } from '../utils/format';
import {
  OBSERVATION_LEVEL_UNAVAILABLE,
  observationLevelLabels,
  occurrenceRateText,
  qualityLabels,
  resultTerms,
} from '../utils/labels';
import { observationTone } from '../components/charts/chartShared';

export interface DashboardPageProps {
  /** 최근 7일 창의 기준 시각. 테스트에서 고정하며 실제 화면에서는 마운트 시각을 쓴다. */
  now?: Date;
}

const SIMULATED_NOTICE =
  '시뮬레이션 세션의 결과입니다. 실기기 측정이 아니므로 보행 해석에 사용하지 마세요.';

// 홈 대시보드. 가장 최근 완료된 결과(useLatestResult)와 최근 7일 완료 세션 요약(useHistorySummary)만
// 그리며, 프론트에서 임계값·판정 문구를 만들지 않는다(관찰 단계·품질 단계 배지는 백엔드 라벨).
export function DashboardPage({ now }: DashboardPageProps) {
  const { user } = useAuth();
  const devices = useDevices();
  const latest = useLatestResult();
  const history = useHistorySummary(now);

  const leftDevice = pickDevice(devices.data, latest.session?.leftDeviceId, 'LEFT');
  const rightDevice = pickDevice(devices.data, latest.session?.rightDeviceId, 'RIGHT');
  const leftLayout = useSensorLayout(leftDevice?.sensorLayoutVersion);
  const rightLayout = useSensorLayout(rightDevice?.sensorLayoutVersion);
  const leftLayoutStatus = sensorLayoutStatus({ layout: leftLayout, devices, device: leftDevice });
  const rightLayoutStatus = sensorLayoutStatus({
    layout: rightLayout,
    devices,
    device: rightDevice,
  });

  const noDevices = devices.data?.length === 0;
  const noCompleted = !latest.isPending && !latest.isError && latest.session === null;

  return (
    <div className="page-stack dashboard-page">
      <PageHeader
        eyebrow="HOME"
        title={`${user?.name ?? '사용자'}님의 걸음을 살펴볼까요?`}
        description={`가장 최근 완료된 측정 결과와 최근 ${HISTORY_WINDOW_DAYS}일 흐름을 한눈에 확인합니다.`}
        action={
          <Link className="button button--large" to="/measurements/new">
            <Icon name="plus" />새 측정
          </Link>
        }
      />

      {latest.isPending ? (
        <div className="centered-status">
          <Spinner label={latest.session ? '최근 결과 분석 중' : '최근 결과 불러오는 중'} />
        </div>
      ) : null}
      {latest.isError ? (
        <ErrorPanel error={latest.error} retry={() => void latest.refetch()} />
      ) : null}
      {devices.isError ? (
        <ErrorPanel error={devices.error} retry={() => void devices.refetch()} />
      ) : null}

      {noCompleted ? (
        <section className="hero-card dashboard-hero">
          <div className="hero-card__content">
            <span className="hero-card__icon">
              <Icon name="activity" />
            </span>
            <div>
              <p className="eyebrow eyebrow--light">FIRST MEASUREMENT</p>
              <h2>아직 완료된 측정이 없어요.</h2>
              <p>
                두 인솔을 연결한 뒤 평소처럼 걸어보세요. 첫 측정을 마치면 이곳에서 결과와 흐름을
                확인할 수 있습니다.
              </p>
            </div>
          </div>
          <Link className="button button--light" to="/measurements/new">
            첫 측정 시작
            <Icon name="arrow" />
          </Link>
        </section>
      ) : null}

      {noDevices ? (
        <section className="content-card dashboard-devices" aria-labelledby="device-summary-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">MY INSOLES</p>
              <h2 id="device-summary-title">내 인솔</h2>
            </div>
            <Link className="text-link" to="/devices">
              전체 보기
              <Icon name="arrow" />
            </Link>
          </div>
          <StatePanel
            compact
            icon="device"
            title="등록된 인솔이 없어요"
            description="왼발과 오른발 인솔을 등록한 뒤 측정을 시작할 수 있습니다."
            action={
              <Link className="button button--secondary" to="/devices">
                인솔 등록
              </Link>
            }
          />
        </section>
      ) : null}

      {latest.session && latest.result ? (
        <ResultDashboard
          history={history}
          leftLayout={leftLayout.data}
          leftLayoutStatus={leftLayoutStatus}
          result={latest.result}
          rightLayout={rightLayout.data}
          rightLayoutStatus={rightLayoutStatus}
          session={latest.session}
        />
      ) : null}
    </div>
  );
}

// 세션의 기기를 우선하고, 목록에 없으면(기기 교체 등) 같은 발의 기기 배치를 쓴다.
// 완료 세션이 없으면 배치를 조회하지 않는다.
const pickDevice = (
  devices: readonly DeviceResponse[] | undefined,
  deviceId: string | undefined,
  side: FootSide,
): DeviceResponse | undefined =>
  deviceId === undefined
    ? undefined
    : (devices?.find((device) => device.deviceId === deviceId) ??
      devices?.find((device) => device.footSide === side));

function ResultDashboard({
  result,
  session,
  history,
  leftLayout,
  rightLayout,
  leftLayoutStatus,
  rightLayoutStatus,
}: {
  result: AnalysisResultResponse;
  session: MeasurementHistoryItem;
  history: ReturnType<typeof useHistorySummary>;
  leftLayout: ReturnType<typeof useSensorLayout>['data'];
  rightLayout: ReturnType<typeof useSensorLayout>['data'];
  leftLayoutStatus: LayoutStatus;
  rightLayoutStatus: LayoutStatus;
}) {
  const heatmapTitleId = useId();
  const regionTitleId = useId();
  const gaitTitleId = useId();
  const patternTitleId = useId();
  const trendTitleId = useId();
  const recoTitleId = useId();
  const metricSelectId = useId();
  const [metricKey, setMetricKey] = useState<TrendMetricKey>('symmetryIndex');

  const { gaitSummary, pressureDistribution, dataQuality, patterns } = result;
  const leftShare = pressureDistribution.leftLoadSharePct ?? null;
  const rightShare = pressureDistribution.rightLoadSharePct ?? null;
  const hasLoadShare = leftShare !== null && rightShare !== null;
  const asymmetry =
    result.observationSummary?.find((item) => item.code === 'LEFT_RIGHT_ASYMMETRY') ?? null;
  const levelCounts = countPatternLevels(patterns);
  const qualityComparison = compareQuality(
    dataQuality.score,
    otherQualityScores(history.items, session.sessionId),
  );
  const leftSensorShare = pressureDistribution.leftSensorSharePct ?? null;
  const rightSensorShare = pressureDistribution.rightSensorSharePct ?? null;
  const shareMax = sensorShareMax(leftSensorShare, rightSensorShare);
  const meanStride = gaitSummary.meanStrideTimeMs ?? null;
  const strideValues = strideSeries(history.items);
  const points = trendPoints(history.items, metricKey);
  const metric = trendMetrics[metricKey];
  const recommendations = result.recommendations.slice(0, 3);

  return (
    <>
      <p className="dashboard-source">
        <Icon name="check" />
        {formatDateTime(result.createdAt)} 측정 결과 · 분석 버전 {result.algorithmVersion}
        <Link className="text-link" to={`/measurements/${session.sessionId}/result`}>
          전체 결과 보기
          <Icon name="arrow" />
        </Link>
      </p>

      {session.sourceType === 'SIMULATED' ? (
        <p className="notice notice--info" role="status">
          <Icon name="alert" />
          {SIMULATED_NOTICE}
        </p>
      ) : null}

      <div className="kpi-grid">
        <KpiCard
          icon={<Icon name="foot" />}
          label="좌우 신호 비율"
          sub="왼발 : 오른발 (%)"
          tone="left"
          value={loadShareText(leftShare, rightShare)}
        />
        <KpiCard
          {...(asymmetry
            ? {
                badge: {
                  tone: observationTone(asymmetry.observationLevel),
                  text: observationLevelLabels[asymmetry.observationLevel],
                },
              }
            : {})}
          icon={<Icon name="asymmetry" />}
          label="좌우 대칭 지수"
          sub={
            asymmetry ? '접촉 시간 차이 %' : `접촉 시간 차이 % · ${OBSERVATION_LEVEL_UNAVAILABLE}`
          }
          value={gaitSummary.symmetryIndex}
        />
        <KpiCard
          icon={<Icon name="activity" />}
          label="관찰된 패턴"
          sub={patternCountSub(levelCounts)}
          tone="neutral"
          unit="건"
          value={patterns.length}
        />
        <KpiCard
          badge={{ tone: qualityTone(dataQuality.level), text: qualityLabels[dataQuality.level] }}
          icon={<Icon name="gauge" />}
          label="데이터 품질"
          sub={qualityComparisonText(qualityComparison)}
          tone={qualityTone(dataQuality.level)}
          unit="점"
          value={dataQuality.score}
        />
      </div>

      <div className="dashboard-row dashboard-row--pressure">
        <section className="content-card" aria-labelledby={heatmapTitleId}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">SESSION AVERAGE</p>
              <h2 id={heatmapTitleId}>양발 {resultTerms.signalShare}</h2>
              <p>세션 접촉 프레임 평균이며 두 발은 같은 색 축을 씁니다.</p>
            </div>
          </div>
          <div className="dashboard-heatmaps">
            <SessionShareHeatmap
              layout={leftLayout}
              layoutStatus={leftLayoutStatus}
              shareMax={shareMax}
              sharePct={leftSensorShare}
              side="LEFT"
            />
            <SessionShareHeatmap
              layout={rightLayout}
              layoutStatus={rightLayoutStatus}
              shareMax={shareMax}
              sharePct={rightSensorShare}
              side="RIGHT"
            />
            <PressureLegend mode="share" orientation="vertical" />
          </div>
        </section>

        <section className="content-card" aria-labelledby={regionTitleId}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">REGIONS</p>
              <h2 id={regionTitleId}>부위별 {resultTerms.signalShare}</h2>
            </div>
          </div>
          <RegionDistributionChart rows={regionRows(pressureDistribution)} />
          <p className="metric-note">
            센서 값은 세션 adcMax 기준 상대 신호이며 보정된 압력이나 체중이 아닙니다.
          </p>
        </section>
      </div>

      <div className="dashboard-row dashboard-row--gait">
        <section className="content-card" aria-labelledby={gaitTitleId}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">GAIT</p>
              <h2 id={gaitTitleId}>보행 분석</h2>
            </div>
          </div>
          <ContactTimeBars
            leftMs={gaitSummary.leftContactTimeMs}
            rightMs={gaitSummary.rightContactTimeMs}
          />
          <dl className="dashboard-metrics">
            <div className="dashboard-metric">
              <dt>분당 접촉 횟수</dt>
              <dd>
                {formatNumber(gaitSummary.cadence)}
                <span className="dashboard-metric__unit">회/분</span>
              </dd>
            </div>
            <div className="dashboard-metric">
              <dt>스트라이드 시간(추정)</dt>
              <dd className={meanStride === null ? 'dashboard-metric__value--unavailable' : ''}>
                {meanStride === null ? (
                  UNAVAILABLE_TEXT
                ) : (
                  <>
                    {formatNumber(meanStride)}
                    <span className="dashboard-metric__unit">밀리초</span>
                  </>
                )}
              </dd>
              <Sparkline
                ariaLabel={`최근 ${HISTORY_WINDOW_DAYS}일 스트라이드 시간(추정) 흐름`}
                values={strideValues}
              />
            </div>
          </dl>
          <div className="dashboard-donut">
            <Donut
              ariaLabel={
                hasLoadShare
                  ? `좌우 신호 비율 왼발 ${formatNumber(leftShare, 0)}%, 오른발 ${formatNumber(rightShare, 0)}%`
                  : `좌우 신호 비율 ${UNAVAILABLE_TEXT}`
              }
              centerLabel={hasLoadShare ? loadShareText(leftShare, rightShare) : '—'}
              leftPct={leftShare ?? 0}
              rightPct={rightShare ?? 0}
              size={120}
            />
          </div>
          <p className="metric-note">
            접촉 창은 센서 신호 기준 접촉 구간이며 스트라이드 시간은 접촉 구간 기반 추정값입니다.
          </p>
        </section>

        <MovementCard compact summary={result.movementSummary} />

        <section className="content-card" aria-labelledby={patternTitleId}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">PATTERNS</p>
              <h2 id={patternTitleId}>관찰된 패턴</h2>
            </div>
          </div>
          <PatternList
            emptyText="이번 측정에서 표시할 관찰 패턴이 없습니다."
            patterns={patterns.map((pattern) => ({
              code: pattern.code,
              title: pattern.title,
              message: pattern.message,
              severity: pattern.severity,
              observationLevel: pattern.observationLevel ?? null,
              occurrenceText: occurrenceRateText(pattern),
            }))}
            renderIcon={(code) => <Icon name={patternIconName(code)} />}
          />
        </section>
      </div>

      <div className="dashboard-row dashboard-row--trend">
        <section className="content-card" aria-labelledby={trendTitleId}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">LAST {HISTORY_WINDOW_DAYS} DAYS</p>
              <h2 id={trendTitleId}>최근 {HISTORY_WINDOW_DAYS}일 변화</h2>
            </div>
            <label className="field dashboard-trend__select" htmlFor={metricSelectId}>
              <span>지표</span>
              <select
                id={metricSelectId}
                onChange={(event) => {
                  if (isTrendMetricKey(event.target.value)) setMetricKey(event.target.value);
                }}
                value={metricKey}
              >
                {trendMetricKeys.map((key) => (
                  <option key={key} value={key}>
                    {trendMetrics[key].label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {history.isPending ? <Spinner label="최근 기록 불러오는 중" /> : null}
          {history.isError ? (
            <ErrorPanel error={history.error} retry={() => void history.refetch()} />
          ) : null}
          {!history.isPending && !history.isError ? (
            <TrendLineChart
              ariaSummary={`최근 ${HISTORY_WINDOW_DAYS}일 완료 측정 ${points.length}회의 ${metric.label}`}
              emptyText={`최근 ${HISTORY_WINDOW_DAYS}일에 표시할 ${metric.label} 값이 없습니다.`}
              points={points}
              unit={metric.unit}
              valueHeader={metric.label}
            />
          ) : null}
        </section>

        <section className="content-card" aria-labelledby={recoTitleId}>
          <div className="section-heading">
            <div>
              <p className="eyebrow">GUIDE</p>
              <h2 id={recoTitleId}>추천 운동</h2>
            </div>
          </div>
          {recommendations.length === 0 ? (
            <p className="dashboard-reco__empty">이번 결과에 연결된 운동 가이드가 없습니다.</p>
          ) : (
            <div className="dashboard-reco">
              {recommendations.map((guide) => (
                <RecommendationTile
                  code={guide.code}
                  durationMinutes={guide.durationMinutes}
                  icon={<Icon name="guide" />}
                  key={guide.code}
                  summary={guide.summary}
                  title={guide.title}
                />
              ))}
            </div>
          )}
          <Link className="button button--secondary dashboard-reco__cta" to="/recommendations">
            결과와 연결된 운동 가이드 보기
            <Icon name="arrow" />
          </Link>
        </section>
      </div>
    </>
  );
}
