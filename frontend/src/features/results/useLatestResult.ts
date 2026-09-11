import { useQuery } from '@tanstack/react-query';
import { ApiError } from '../../api/client';
import { queryKeys } from '../../api/queries';
import { measurementApi } from '../../api/services';
import type { AnalysisResultResponse, MeasurementHistoryItem } from '../../api/types';
import { RESULT_POLL_INTERVAL_MS, shouldPollResult } from './resultPolling';

// ResultsEntryPage와 같은 조회 조건(가장 최근 COMPLETED 1건). 쿼리 키를 공유해 캐시를 재사용한다.
export const LATEST_COMPLETED_PARAMS = { page: 0, size: 1, status: 'COMPLETED' } as const;

export interface LatestResultState {
  /** 가장 최근 완료된 세션. 완료된 측정이 없으면 null. */
  session: MeasurementHistoryItem | null;
  /** 해당 세션의 분석 결과. 세션이 없거나 아직 202(processing)이면 null. */
  result: AnalysisResultResponse | null;
  /** 세션 목록 또는 결과를 아직 받지 못한 상태(202 processing 폴링 중 포함). */
  isPending: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => Promise<void>;
}

// 최근 완료 세션 → 결과 순서로 두 쿼리를 잇는다. 결과가 202(processing)이면 ResultPage와 같은
// 간격으로 폴링하고, 4xx는 재시도하지 않는다.
export function useLatestResult(): LatestResultState {
  const latest = useQuery({
    queryKey: queryKeys.measurements(LATEST_COMPLETED_PARAMS),
    queryFn: () => measurementApi.list(LATEST_COMPLETED_PARAMS),
  });
  const session = latest.data?.items[0] ?? null;
  const sessionId = session?.sessionId;

  const result = useQuery({
    queryKey: queryKeys.result(sessionId ?? 'missing'),
    queryFn: () => measurementApi.result(sessionId ?? ''),
    enabled: Boolean(sessionId),
    refetchInterval: (query) =>
      shouldPollResult(query.state.data) ? RESULT_POLL_INTERVAL_MS : false,
    refetchIntervalInBackground: false,
    retry: (failureCount, error) =>
      !(error instanceof ApiError && error.status !== null && error.status < 500) &&
      failureCount < 2,
  });

  const hasSession = Boolean(sessionId);
  const processing = result.data?.kind === 'processing';
  const refetch = async (): Promise<void> => {
    await latest.refetch();
    if (hasSession) await result.refetch();
  };

  return {
    session,
    result: result.data?.kind === 'completed' ? result.data.data : null,
    isPending: latest.isPending || (hasSession && (result.isPending || processing)),
    isError: latest.isError || (hasSession && result.isError),
    error: latest.error ?? (hasSession ? result.error : null),
    refetch,
  };
}
