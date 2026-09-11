import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { queryKeys } from '../../api/queries';
import { measurementApi } from '../../api/services';
import type { MeasurementHistoryItem } from '../../api/types';
import { historyParams } from './dashboardMetrics';

export interface HistorySummaryState {
  /** 최근 7일의 완료 세션(API 순서: 최신순). 아직 받지 못했으면 빈 배열. */
  items: MeasurementHistoryItem[];
  isPending: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => Promise<void>;
}

// 최근 7일 완료 세션 요약(계약 1.2.0 summary metrics). 기준 시각 now를 넘기면 창의 시작이 고정되어
// 테스트가 결정적이다. 넘기지 않으면 마운트 시각을 한 번만 잡아 쿼리 키가 렌더마다 바뀌지 않게 한다.
export function useHistorySummary(now?: Date): HistorySummaryState {
  const [mountedAtMs] = useState(() => Date.now());
  const nowMs = now?.getTime() ?? mountedAtMs;
  const params = useMemo(() => historyParams(new Date(nowMs)), [nowMs]);

  const query = useQuery({
    queryKey: queryKeys.measurements(params),
    queryFn: () => measurementApi.list(params),
  });

  return {
    items: query.data?.items ?? [],
    isPending: query.isPending,
    isError: query.isError,
    error: query.error,
    refetch: async () => {
      await query.refetch();
    },
  };
}
