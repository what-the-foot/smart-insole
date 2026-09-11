import { useQuery } from '@tanstack/react-query';
import { Link, Navigate } from 'react-router-dom';
import { queryKeys } from '../api/queries';
import { measurementApi } from '../api/services';
import { ErrorPanel, Spinner, StatePanel } from '../components/StatusUi';

const latestCompletedParams = { page: 0, size: 1, status: 'COMPLETED' } as const;

// '결과 분석' 진입점: 가장 최근 완료된 측정의 결과로 이동한다.
export function ResultsEntryPage() {
  const latest = useQuery({
    queryKey: queryKeys.measurements(latestCompletedParams),
    queryFn: () => measurementApi.list(latestCompletedParams),
  });

  if (latest.isPending)
    return (
      <div className="centered-status">
        <Spinner label="최근 결과 확인 중" />
      </div>
    );
  if (latest.isError)
    return <ErrorPanel error={latest.error} retry={() => void latest.refetch()} />;

  const session = latest.data.items[0];
  if (session) return <Navigate replace to={`/measurements/${session.sessionId}/result`} />;

  return (
    <StatePanel
      icon="chart"
      title="아직 완료된 측정이 없어요"
      description="측정을 완료하면 분석 결과가 여기에 표시됩니다. 두 인솔을 연결하고 첫 측정을 시작해 보세요."
      action={
        <Link className="button" to="/measurements/new">
          새 측정
        </Link>
      }
    />
  );
}
