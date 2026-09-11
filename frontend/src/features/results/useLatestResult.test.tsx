import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ApiError } from '../../api/client';
import { measurementApi } from '../../api/services';
import type {
  AnalysisResultResponse,
  MeasurementHistoryItem,
  MeasurementSessionPage,
} from '../../api/types';
import { LATEST_COMPLETED_PARAMS, useLatestResult } from './useLatestResult';

const session: MeasurementHistoryItem = {
  sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
  status: 'COMPLETED',
  leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
  rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
  sampleRateHz: 50,
  sourceType: 'DEVICE',
  createdAt: '2026-09-11T07:00:00Z',
  primaryPatternCode: null,
  algorithmVersion: 'rule-v1.4.0',
};

const emptyPage: MeasurementSessionPage = {
  items: [],
  page: 0,
  size: 1,
  totalElements: 0,
  totalPages: 0,
};

const onePage: MeasurementSessionPage = {
  ...emptyPage,
  items: [session],
  totalElements: 1,
  totalPages: 1,
};

const completed: AnalysisResultResponse = {
  sessionId: session.sessionId,
  status: 'COMPLETED',
  algorithmVersion: 'rule-v1.4.0',
  dataQuality: { score: 92, level: 'GOOD', missingFrameRate: 0.003, flags: [] },
  gaitSummary: {
    validStepCount: 20,
    cadence: 108.2,
    leftContactTimeMs: 642,
    rightContactTimeMs: 608,
    symmetryIndex: 5.3,
  },
  pressureDistribution: {
    leftMedialRatio: 0.61,
    leftLateralRatio: 0.39,
    rightMedialRatio: 0.58,
    rightLateralRatio: 0.42,
    leftHeelRatio: 0.35,
    rightHeelRatio: 0.34,
    leftMidfootRatio: 0.25,
    rightMidfootRatio: 0.26,
    leftForefootRatio: 0.4,
    rightForefootRatio: 0.4,
    leftPeakPressure: 88.4,
    rightPeakPressure: 91.2,
    leftMeanCoP: { x: 0.42, y: 0.67 },
    rightMeanCoP: null,
  },
  patterns: [],
  observationSummary: null,
  movementSummary: {
    imuCoverage: 0.92,
    referenceMethod: 'QUIET_STANDING',
    left: {
      frontalTiltDeg: 3.2,
      sagittalRangeDeg: 41.7,
      transverseRangeDeg: 12.5,
      swingPeakAngularVelocityDps: 318.4,
      windowCount: 18,
    },
    right: null,
  },
  recommendations: [],
  disclaimer: '본 결과는 의료 진단이 아니며, 통증이 지속되면 전문가의 평가가 필요합니다.',
  createdAt: '2026-09-11T07:16:03Z',
};

const renderLatest = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return renderHook(() => useLatestResult(), { wrapper });
};

describe('useLatestResult', () => {
  it('가장 최근 COMPLETED 세션 1건을 조회한 뒤 그 결과를 함께 돌려준다', async () => {
    const list = vi.spyOn(measurementApi, 'list').mockResolvedValue(onePage);
    const result = vi
      .spyOn(measurementApi, 'result')
      .mockResolvedValue({ kind: 'completed', data: completed });
    const { result: hook, unmount } = renderLatest();

    expect(hook.current.isPending).toBe(true);
    expect(hook.current.session).toBeNull();
    expect(hook.current.result).toBeNull();

    await waitFor(() => expect(hook.current.isPending).toBe(false));
    expect(LATEST_COMPLETED_PARAMS).toEqual({ page: 0, size: 1, status: 'COMPLETED' });
    expect(list).toHaveBeenCalledWith({ page: 0, size: 1, status: 'COMPLETED' });
    expect(result).toHaveBeenCalledWith(session.sessionId);
    expect(hook.current.session).toEqual(session);
    expect(hook.current.result).toEqual(completed);
    expect(hook.current.result?.movementSummary?.left?.windowCount).toBe(18);
    expect(hook.current.isError).toBe(false);
    expect(hook.current.error).toBeNull();
    unmount();
  });

  it('완료된 측정이 없으면 결과 API를 호출하지 않고 session·result가 null이다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue(emptyPage);
    const result = vi.spyOn(measurementApi, 'result');
    const { result: hook, unmount } = renderLatest();

    await waitFor(() => expect(hook.current.isPending).toBe(false));
    expect(hook.current.session).toBeNull();
    expect(hook.current.result).toBeNull();
    expect(hook.current.isError).toBe(false);
    expect(result).not.toHaveBeenCalled();
    unmount();
  });

  it('결과가 아직 202(processing)이면 result는 null이고 pending 상태를 유지한다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue(onePage);
    const result = vi.spyOn(measurementApi, 'result').mockResolvedValue({
      kind: 'processing',
      data: { sessionId: session.sessionId, status: 'PROCESSING', message: '분석 중' },
    });
    const { result: hook, unmount } = renderLatest();

    await waitFor(() => expect(result).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(hook.current.session).toEqual(session));
    expect(hook.current.result).toBeNull();
    expect(hook.current.isPending).toBe(true);
    expect(hook.current.isError).toBe(false);
    unmount();
  });

  it('세션 목록 조회가 실패하면 isError·error를 노출하고 refetch로 다시 시도한다', async () => {
    const failure = new ApiError({ message: '목록을 불러오지 못했습니다.', status: 500 });
    const list = vi
      .spyOn(measurementApi, 'list')
      .mockRejectedValueOnce(failure)
      .mockResolvedValue(onePage);
    vi.spyOn(measurementApi, 'result').mockResolvedValue({ kind: 'completed', data: completed });
    const { result: hook, unmount } = renderLatest();

    await waitFor(() => expect(hook.current.isError).toBe(true));
    expect(hook.current.error).toBe(failure);
    expect(hook.current.session).toBeNull();
    expect(hook.current.result).toBeNull();

    await act(async () => {
      await hook.current.refetch();
    });
    await waitFor(() => expect(hook.current.result).toEqual(completed));
    expect(list).toHaveBeenCalledTimes(2);
    expect(hook.current.isError).toBe(false);
    expect(hook.current.error).toBeNull();
    unmount();
  });

  it('결과 조회의 4xx 오류는 재시도 없이 isError·error로 노출한다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue(onePage);
    const failure = new ApiError({
      message: '결과가 없습니다.',
      code: 'RESULT_NOT_FOUND',
      status: 404,
    });
    const result = vi.spyOn(measurementApi, 'result').mockRejectedValue(failure);
    const { result: hook, unmount } = renderLatest();

    await waitFor(() => expect(hook.current.isError).toBe(true));
    expect(hook.current.session).toEqual(session);
    expect(hook.current.result).toBeNull();
    expect(hook.current.error).toBe(failure);
    expect(hook.current.isPending).toBe(false);
    expect(result).toHaveBeenCalledTimes(1);
    unmount();
  });
});
