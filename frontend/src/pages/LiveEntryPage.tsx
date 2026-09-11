import { useQuery } from '@tanstack/react-query';
import { Navigate } from 'react-router-dom';
import { queryKeys } from '../api/queries';
import { measurementApi } from '../api/services';
import { ErrorPanel, Spinner } from '../components/StatusUi';

const measuringParams = { page: 0, size: 1, status: 'MEASURING' } as const;
const createdParams = { page: 0, size: 1, status: 'CREATED' } as const;

// '실시간 추적' 진입점: 진행 중(MEASURING) 세션 → 준비된(CREATED) 세션 → 새 측정 순으로 이동한다.
export function LiveEntryPage() {
  const measuring = useQuery({
    queryKey: queryKeys.measurements(measuringParams),
    queryFn: () => measurementApi.list(measuringParams),
  });
  const measuringEmpty = measuring.data?.items.length === 0;
  const created = useQuery({
    queryKey: queryKeys.measurements(createdParams),
    queryFn: () => measurementApi.list(createdParams),
    enabled: measuringEmpty,
  });

  const active = measuring.data?.items[0];
  if (active) return <Navigate replace to={`/measurements/${active.sessionId}/live`} />;
  const prepared = created.data?.items[0];
  if (prepared) return <Navigate replace to={`/measurements/${prepared.sessionId}/live`} />;
  if (measuringEmpty && created.data) return <Navigate replace to="/measurements/new" />;

  if (measuring.isError)
    return <ErrorPanel error={measuring.error} retry={() => void measuring.refetch()} />;
  if (created.isError)
    return <ErrorPanel error={created.error} retry={() => void created.refetch()} />;
  return (
    <div className="centered-status">
      <Spinner label="진행 중인 측정 확인 중" />
    </div>
  );
}
