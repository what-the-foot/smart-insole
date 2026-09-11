import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { queryKeys } from '../api/queries';
import { measurementApi } from '../api/services';
import type { MeasurementSessionResponse, RecommendationSummary } from '../api/types';
import { Icon } from '../components/Icon';
import { ErrorPanel, PageHeader, Spinner, StatePanel, StatusBadge } from '../components/StatusUi';
import { formatDateTime } from '../utils/format';
import { sessionSourceBadge } from '../utils/labels';

// 시뮬레이션 세션은 실기기 결과와 구분해 보여준다(DEC-028). ResultContent와 같은 배지·문구를 쓴다.
type SessionMeta = Pick<MeasurementSessionResponse, 'sourceType' | 'sampleRateHz'>;

const latestCompletedParams = { page: 0, size: 1, status: 'COMPLETED' } as const;

const EMPTY_DESCRIPTION = '측정을 완료하면 결과와 연결된 운동 가이드가 표시됩니다';

// 운동 가이드 목록 API는 없으므로(계약 1.1) 최근 완료 결과의 recommendations[]를 목록으로 보여준다.
export function RecommendationsIndexPage() {
  const latest = useQuery({
    queryKey: queryKeys.measurements(latestCompletedParams),
    queryFn: () => measurementApi.list(latestCompletedParams),
  });
  const session = latest.data?.items[0];
  const sessionId = session?.sessionId;
  const result = useQuery({
    queryKey: queryKeys.result(sessionId ?? 'missing'),
    queryFn: () => measurementApi.result(sessionId ?? ''),
    enabled: Boolean(sessionId),
  });

  return (
    <div className="page-stack recommendations-index-page">
      <PageHeader
        eyebrow="MOVEMENT GUIDE"
        title="운동 가이드"
        description="최근 완료된 측정 결과와 연결된 운동 가이드입니다. 안전 안내를 먼저 확인하고 천천히 진행하세요."
      />
      {latest.isPending || (sessionId !== undefined && result.isPending) ? (
        <div className="centered-status">
          <Spinner label="운동 가이드 불러오는 중" />
        </div>
      ) : null}
      {latest.isError ? (
        <ErrorPanel error={latest.error} retry={() => void latest.refetch()} />
      ) : null}
      {result.isError ? (
        <ErrorPanel error={result.error} retry={() => void result.refetch()} />
      ) : null}
      {latest.data && !session ? (
        <StatePanel
          icon="guide"
          title="연결된 운동 가이드가 없어요"
          description={EMPTY_DESCRIPTION}
          action={
            <Link className="button" to="/measurements/new">
              새 측정
            </Link>
          }
        />
      ) : null}
      {session && result.data?.kind === 'processing' ? (
        <StatePanel
          icon="clock"
          title="최근 측정을 아직 분석하고 있어요"
          description="분석이 끝나면 결과와 연결된 운동 가이드를 볼 수 있습니다."
          action={
            <Link
              className="button button--secondary"
              to={`/measurements/${session.sessionId}/result`}
            >
              결과 화면으로
            </Link>
          }
        />
      ) : null}
      {session && result.data?.kind === 'completed' ? (
        <GuideList
          recommendations={result.data.data.recommendations}
          sessionId={session.sessionId}
          measuredAt={session.createdAt}
          session={{ sourceType: session.sourceType, sampleRateHz: session.sampleRateHz }}
        />
      ) : null}
    </div>
  );
}

function GuideList({
  recommendations,
  sessionId,
  measuredAt,
  session,
}: {
  recommendations: readonly RecommendationSummary[];
  sessionId: string;
  measuredAt: string;
  session: SessionMeta;
}) {
  const simulated = session.sourceType === 'SIMULATED';
  return (
    <>
      <section className="content-card" aria-labelledby="guide-source-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">LATEST RESULT</p>
            <h2 id="guide-source-title">기준 측정</h2>
            <p>
              {formatDateTime(measuredAt)} 측정 결과에서 제안된 가이드입니다.{' '}
              <StatusBadge tone={simulated ? 'neutral' : 'info'}>
                {sessionSourceBadge(session)}
              </StatusBadge>
            </p>
          </div>
          <Link className="text-link" to={`/measurements/${sessionId}/result`}>
            결과 보기
            <Icon name="arrow" />
          </Link>
        </div>
        {simulated ? (
          <p className="notice notice--info" role="status">
            <Icon name="alert" />
            시뮬레이션 세션의 결과입니다. 실기기 측정이 아니므로 보행 해석에 사용하지 마세요.
          </p>
        ) : null}
        {recommendations.length === 0 ? (
          <StatePanel
            compact
            icon="guide"
            title="이번 결과에 연결된 운동 가이드가 없어요"
            description={EMPTY_DESCRIPTION}
          />
        ) : (
          <ul className="guide-tiles">
            {recommendations.map((guide) => (
              <li key={guide.code}>
                <Link
                  className="guide-tile"
                  to={`/recommendations/${encodeURIComponent(guide.code)}`}
                >
                  <span className="guide-tile__icon">
                    <Icon name="guide" />
                  </span>
                  <span className="guide-tile__body">
                    <strong>{guide.title}</strong>
                    <span>{guide.summary}</span>
                  </span>
                  <span className="guide-tile__meta">
                    <Icon name="clock" />
                    {guide.durationMinutes}분
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      <aside className="safety-card" aria-labelledby="guide-safety-title">
        <Icon name="shield" />
        <div>
          <h2 id="guide-safety-title">운동 전 안전 안내</h2>
          <p>
            운동 가이드는 관찰된 족압 패턴을 바탕으로 제안되며 치료나 교정 효과를 보장하지 않습니다.
            통증이나 불편함이 느껴지면 즉시 중단하고, 지속되면 전문가 상담을 권장합니다.
          </p>
        </div>
      </aside>
    </>
  );
}
