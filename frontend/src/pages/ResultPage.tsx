import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { queryKeys, useMeasurement } from '../api/queries';
import { measurementApi } from '../api/services';
import { ApiError } from '../api/client';
import { ErrorPanel, Spinner, StatePanel } from '../components/StatusUi';
import { ResultContent } from '../features/results/ResultContent';
import { RESULT_POLL_INTERVAL_MS, shouldPollResult } from '../features/results/resultPolling';

export function ResultPage() {
  const { sessionId } = useParams();
  const measurement = useMeasurement(sessionId);
  const canLoadResult =
    measurement.data?.status === 'PROCESSING' || measurement.data?.status === 'COMPLETED';
  const result = useQuery({
    queryKey: queryKeys.result(sessionId ?? 'missing'),
    queryFn: () => measurementApi.result(sessionId ?? ''),
    enabled: Boolean(sessionId) && canLoadResult,
    refetchInterval: (query) =>
      shouldPollResult(query.state.data) ? RESULT_POLL_INTERVAL_MS : false,
    refetchIntervalInBackground: false,
    retry: (failureCount, error) =>
      !(error instanceof ApiError && error.status !== null && error.status < 500) &&
      failureCount < 2,
  });

  if (!sessionId)
    return (
      <StatePanel
        title="결과 ID가 없습니다"
        description="측정 기록에서 결과를 다시 선택해 주세요."
        action={
          <Link className="button" to="/history">
            기록으로
          </Link>
        }
      />
    );
  if (measurement.isPending) return <ProcessingPanel message="측정 상태를 확인하고 있습니다." />;
  if (measurement.isError)
    return <ErrorPanel error={measurement.error} retry={() => void measurement.refetch()} />;
  if (measurement.data.status === 'FAILED')
    return (
      <StatePanel
        title="분석을 완료하지 못했어요"
        description="인솔 연결과 데이터 품질을 확인한 뒤 다시 측정해 주세요."
        action={
          <Link className="button" to="/measurements/new">
            새 측정 시작
          </Link>
        }
      />
    );
  if (measurement.data.status === 'CANCELLED')
    return (
      <StatePanel
        title="취소된 측정입니다"
        description="취소된 측정에는 분석 결과가 생성되지 않습니다."
        action={
          <Link className="button" to="/history">
            기록으로
          </Link>
        }
      />
    );
  if (measurement.data.status === 'CREATED' || measurement.data.status === 'MEASURING')
    return (
      <StatePanel
        icon="activity"
        title={
          measurement.data.status === 'CREATED'
            ? '아직 측정을 시작하지 않았어요'
            : '측정이 진행 중이에요'
        }
        description="측정 화면에서 시작하거나 현재 측정을 마친 뒤 결과를 확인해 주세요."
        action={
          <Link className="button" to={`/measurements/${sessionId}/live`}>
            측정 화면으로
          </Link>
        }
      />
    );
  if (result.isPending) return <ProcessingPanel />;
  if (result.isError)
    return <ErrorPanel error={result.error} retry={() => void result.refetch()} />;
  if (result.data.kind === 'processing')
    return <ProcessingPanel message={result.data.data.message} />;
  return <ResultContent result={result.data.data} session={measurement.data} />;
}

function ProcessingPanel({
  message = '측정 데이터를 차분히 분석하고 있습니다.',
}: {
  message?: string;
}) {
  return (
    <div className="processing-page">
      <div className="processing-visual" aria-hidden="true">
        <span />
        <span />
        <span />
        <div className="processing-foot" />
      </div>
      <p className="eyebrow">ANALYSIS IN PROGRESS</p>
      <h1>걸음 패턴을 정리하고 있어요.</h1>
      <p>
        {message}
        <br />
        완료되면 이 화면이 자동으로 갱신됩니다.
      </p>
      <Spinner label="분석 결과 기다리는 중" />
      <aside>
        <strong>잠시 오래 걸리나요?</strong>
        <p>페이지를 닫아도 분석은 계속됩니다. 나중에 측정 기록에서 다시 확인할 수 있어요.</p>
      </aside>
    </div>
  );
}
