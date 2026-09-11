import { useState, type SyntheticEvent } from 'react';
import { Link } from 'react-router-dom';
import { useMeasurements } from '../api/queries';
import type { MeasurementHistoryItem, MeasurementStatus, QualityLevel } from '../api/types';
import type { MeasurementListParams } from '../api/services';
import { Icon } from '../components/Icon';
import {
  ErrorPanel,
  PageHeader,
  Spinner,
  StatePanel,
  StatusBadge,
  type StatusBadgeTone,
} from '../components/StatusUi';
import { UNAVAILABLE_TEXT } from '../components/charts/chartShared';
import { formatDateTime, formatDuration, formatNumber } from '../utils/format';
import {
  measurementStatusLabels,
  observationPatternCodes,
  patternCodeLabel,
  qualityLabels,
  sourceTypeLabels,
} from '../utils/labels';

const statuses: MeasurementStatus[] = [
  'CREATED',
  'MEASURING',
  'PROCESSING',
  'COMPLETED',
  'CANCELLED',
  'FAILED',
];
// 필터 옵션은 계약(rule-v1.2.0)의 6종만. 폐기 코드(HIGH_MIDFOOT_LOAD 등)는 계약에 없으므로 제공하지 않는다.
const patternCodes = observationPatternCodes;

type HistoryFilters = Omit<MeasurementListParams, 'page' | 'size'>;

const sessionTarget = (session: MeasurementHistoryItem): string => {
  if (session.status === 'MEASURING' || session.status === 'CREATED')
    return `/measurements/${session.sessionId}/live`;
  return `/measurements/${session.sessionId}/result`;
};

const dateBoundary = (value: string, endOfDay = false): string | undefined => {
  if (!value) return undefined;
  return new Date(`${value}T${endOfDay ? '23:59:59.999' : '00:00:00'}`).toISOString();
};

const statusTone = (status: MeasurementStatus): StatusBadgeTone => {
  if (status === 'COMPLETED') return 'positive';
  if (status === 'FAILED') return 'danger';
  if (status === 'CANCELLED') return 'neutral';
  return 'info';
};

// ResultContent와 같은 매핑(GOOD positive / POOR danger / 그 외 warning 톤). 데이터 품질 단계일 뿐 걸음 판정이 아니다.
const qualityTone = (level: QualityLevel): StatusBadgeTone => {
  if (level === 'GOOD') return 'positive';
  if (level === 'POOR') return 'danger';
  return 'warning';
};

const isFiniteNumber = (value: number | null | undefined): value is number =>
  value !== null && value !== undefined && Number.isFinite(value);

// 계약 1.2.0 세션 요약 지표(모두 nullable). 백엔드 값을 그대로 표기하며 판정 문구를 붙이지 않는다.
// 예) '대칭 지수 12.4 · 스트라이드 1.1 s · 좌우 52:48' (formatNumber는 끝의 0을 생략한다)
const sessionMetricsLine = (
  session: Pick<
    MeasurementHistoryItem,
    'symmetryIndex' | 'meanStrideTimeMs' | 'leftLoadSharePct' | 'rightLoadSharePct'
  >,
): string | null => {
  const parts: string[] = [];
  if (isFiniteNumber(session.symmetryIndex)) {
    parts.push(`대칭 지수 ${formatNumber(session.symmetryIndex)}`);
  }
  if (isFiniteNumber(session.meanStrideTimeMs)) {
    parts.push(`스트라이드 ${formatNumber(session.meanStrideTimeMs / 1000, 2)} s`);
  }
  if (isFiniteNumber(session.leftLoadSharePct) && isFiniteNumber(session.rightLoadSharePct)) {
    parts.push(
      `좌우 ${formatNumber(session.leftLoadSharePct, 0)}:${formatNumber(session.rightLoadSharePct, 0)}`,
    );
  }
  return parts.length > 0 ? parts.join(' · ') : null;
};

export function HistoryPage() {
  const [page, setPage] = useState(0);
  const [draftStatus, setDraftStatus] = useState<MeasurementStatus | ''>('');
  const [draftFrom, setDraftFrom] = useState('');
  const [draftTo, setDraftTo] = useState('');
  const [draftMinQualityScore, setDraftMinQualityScore] = useState('');
  const [draftPatternCode, setDraftPatternCode] = useState('');
  const [filters, setFilters] = useState<HistoryFilters>({});
  const [formError, setFormError] = useState<string | null>(null);
  const queryParams: MeasurementListParams = { page, size: 10, ...filters };
  const measurements = useMeasurements(queryParams);
  const activeFilterCount = Object.keys(filters).length;
  const hasFilters = activeFilterCount > 0;

  const applyFilters = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFormError(null);
    const from = dateBoundary(draftFrom);
    const to = dateBoundary(draftTo, true);
    if (from && to && from > to) {
      setFormError('시작 날짜는 종료 날짜보다 늦을 수 없습니다.');
      return;
    }
    const minQualityScore = draftMinQualityScore === '' ? undefined : Number(draftMinQualityScore);
    if (
      minQualityScore !== undefined &&
      (!Number.isInteger(minQualityScore) || minQualityScore < 0 || minQualityScore > 100)
    ) {
      setFormError('최소 품질 점수는 0부터 100 사이의 정수로 입력해 주세요.');
      return;
    }
    setPage(0);
    setFilters({
      ...(draftStatus ? { status: draftStatus } : {}),
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
      ...(minQualityScore !== undefined ? { minQualityScore } : {}),
      ...(draftPatternCode.trim() ? { patternCode: draftPatternCode.trim() } : {}),
    });
  };

  const resetFilters = () => {
    setDraftStatus('');
    setDraftFrom('');
    setDraftTo('');
    setDraftMinQualityScore('');
    setDraftPatternCode('');
    setFilters({});
    setFormError(null);
    setPage(0);
  };

  return (
    <div className="page-stack history-page">
      <PageHeader
        eyebrow="MEASUREMENT HISTORY"
        title="측정 기록"
        description="측정 상태와 품질을 살펴보고 이전 결과로 돌아갈 수 있습니다."
        action={
          <Link className="button" to="/measurements/new">
            <Icon name="plus" />새 측정
          </Link>
        }
      />
      <section className="content-card filter-card filter-toolbar" aria-labelledby="filter-title">
        <form className="filter-toolbar__form" noValidate onSubmit={applyFilters}>
          <div className="filter-toolbar__head">
            <span aria-hidden="true" className="filter-toolbar__icon">
              <Icon name="history" />
            </span>
            <h2 id="filter-title">기록 찾기</h2>
            {hasFilters ? (
              <StatusBadge tone="info">필터 {activeFilterCount}개 적용</StatusBadge>
            ) : (
              <span className="filter-toolbar__hint">조건을 고르고 필터 적용을 누르세요.</span>
            )}
          </div>
          <div className="filter-toolbar__fields">
            <label className="field">
              <span>시작 날짜</span>
              <input
                max={draftTo || undefined}
                onChange={(event) => setDraftFrom(event.target.value)}
                type="date"
                value={draftFrom}
              />
            </label>
            <label className="field">
              <span>종료 날짜</span>
              <input
                min={draftFrom || undefined}
                onChange={(event) => setDraftTo(event.target.value)}
                type="date"
                value={draftTo}
              />
            </label>
            <label className="field">
              <span>측정 상태</span>
              <select
                onChange={(event) => setDraftStatus(event.target.value as MeasurementStatus | '')}
                value={draftStatus}
              >
                <option value="">전체 상태</option>
                {statuses.map((status) => (
                  <option key={status} value={status}>
                    {measurementStatusLabels[status]}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>최소 품질 점수</span>
              <input
                inputMode="numeric"
                max={100}
                min={0}
                onChange={(event) => setDraftMinQualityScore(event.target.value)}
                placeholder="예: 80"
                step={1}
                type="number"
                value={draftMinQualityScore}
              />
            </label>
            <label className="field">
              <span>관찰 패턴</span>
              <select
                onChange={(event) => setDraftPatternCode(event.target.value)}
                value={draftPatternCode}
              >
                <option value="">전체 패턴</option>
                {patternCodes.map((code) => (
                  <option key={code} value={code}>
                    {patternCodeLabel(code)}
                  </option>
                ))}
              </select>
            </label>
            <div className="filter-toolbar__actions">
              <button
                className="button button--secondary button--compact"
                disabled={
                  !hasFilters &&
                  !draftStatus &&
                  !draftFrom &&
                  !draftTo &&
                  !draftMinQualityScore &&
                  !draftPatternCode
                }
                onClick={resetFilters}
                type="button"
              >
                필터 초기화
              </button>
              <button className="button button--compact" type="submit">
                필터 적용
              </button>
            </div>
          </div>
          {formError ? (
            <p className="form-error" role="alert">
              <Icon name="alert" />
              {formError}
            </p>
          ) : null}
        </form>
      </section>

      {measurements.isPending ? (
        <div className="centered-status">
          <Spinner label="측정 기록 불러오는 중" />
        </div>
      ) : null}
      {measurements.isError ? (
        <ErrorPanel error={measurements.error} retry={() => void measurements.refetch()} />
      ) : null}
      {measurements.data?.items.length === 0 ? (
        <StatePanel
          icon="history"
          title="조건에 맞는 기록이 없어요"
          description={
            hasFilters
              ? '필터를 변경하거나 새 측정을 시작해 주세요.'
              : '첫 측정을 완료하면 여기에 기록이 표시됩니다.'
          }
          action={
            hasFilters ? (
              <button className="button button--secondary" onClick={resetFilters} type="button">
                필터 초기화
              </button>
            ) : (
              <Link className="button" to="/measurements/new">
                첫 측정 시작
              </Link>
            )
          }
        />
      ) : null}
      {measurements.data?.items.length ? (
        <section className="history-table-card" aria-label="측정 기록 목록">
          <div className="history-table" role="table">
            <div className="history-row history-row--header" role="row">
              <span role="columnheader">측정 일시</span>
              <span role="columnheader">측정 시간</span>
              <span role="columnheader">데이터 품질</span>
              <span role="columnheader">주요 패턴</span>
              <span role="columnheader">상태</span>
              <span aria-hidden="true" />
            </div>
            {measurements.data.items.map((session) => {
              const duration =
                session.startedAt && session.endedAt
                  ? Math.max(
                      0,
                      new Date(session.endedAt).getTime() - new Date(session.startedAt).getTime(),
                    )
                  : null;
              const metrics = sessionMetricsLine(session);
              // 분석 결과(algorithmVersion)는 있는데 요약 지표가 모두 null이면 이전 분석 결과다.
              const analyzed = Boolean(session.algorithmVersion);
              const qualityLevel = session.dataQualityLevel ?? null;
              const qualityScore = isFiniteNumber(session.dataQualityScore)
                ? session.dataQualityScore
                : null;
              return (
                // 행 전체는 CSS(::after)로 클릭되지만 접근성 트리에서는 첫 셀의 링크만 링크다.
                <div className="history-row" key={session.sessionId} role="row">
                  <span role="cell">
                    <Link
                      aria-label={`${formatDateTime(session.createdAt)} 측정 상세 보기`}
                      className="history-row__link"
                      to={sessionTarget(session)}
                    >
                      <strong>{formatDateTime(session.createdAt)}</strong>
                    </Link>
                    <small>{session.memo?.trim() ? session.memo : '메모 없음'}</small>
                    <small className="history-source">{`${session.sourceType === 'SIMULATED' ? `${sourceTypeLabels.SIMULATED} · ` : ''}${session.sampleRateHz}Hz`}</small>
                    {metrics ? (
                      <small className="history-metrics">{metrics}</small>
                    ) : analyzed ? (
                      <small className="history-metrics history-metrics--empty">
                        요약 지표 {UNAVAILABLE_TEXT}
                      </small>
                    ) : null}
                  </span>
                  <span role="cell">{duration === null ? '—' : formatDuration(duration)}</span>
                  <span className="history-quality" role="cell">
                    {qualityScore === null ? (
                      '분석 전'
                    ) : (
                      <>
                        <strong className="history-quality__score">{qualityScore}점</strong>
                        {qualityLevel ? (
                          <StatusBadge tone={qualityTone(qualityLevel)}>
                            {qualityLabels[qualityLevel]}
                          </StatusBadge>
                        ) : null}
                      </>
                    )}
                  </span>
                  <span role="cell">
                    {session.primaryPatternCode
                      ? patternCodeLabel(session.primaryPatternCode)
                      : '표시 없음'}
                  </span>
                  <span role="cell">
                    <StatusBadge tone={statusTone(session.status)}>
                      {measurementStatusLabels[session.status]}
                    </StatusBadge>
                  </span>
                  <span className="history-row__arrow" role="cell">
                    <Icon name="arrow" />
                  </span>
                </div>
              );
            })}
          </div>
          <nav aria-label="측정 기록 페이지" className="pagination">
            <button
              className="button button--secondary"
              disabled={page <= 0 || measurements.isFetching}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              type="button"
            >
              이전
            </button>
            <span>
              <strong>{measurements.data.page + 1}</strong> /{' '}
              {Math.max(1, measurements.data.totalPages)} 페이지
            </span>
            <button
              className="button button--secondary"
              disabled={page + 1 >= measurements.data.totalPages || measurements.isFetching}
              onClick={() => setPage((current) => current + 1)}
              type="button"
            >
              다음
            </button>
          </nav>
        </section>
      ) : null}
    </div>
  );
}
