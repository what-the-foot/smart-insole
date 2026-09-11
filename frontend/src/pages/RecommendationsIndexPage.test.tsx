import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { measurementApi } from '../api/services';
import type { AnalysisResultResponse, MeasurementSessionPage } from '../api/types';
import { RecommendationsIndexPage } from './RecommendationsIndexPage';

const sessionId = '5803f871-9fca-4a7f-a2c7-9b567a92a6cf';

const completedPage: MeasurementSessionPage = {
  items: [
    {
      sessionId,
      status: 'COMPLETED',
      leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
      rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
      sampleRateHz: 50,
      sourceType: 'DEVICE',
      createdAt: '2026-09-01T07:00:00Z',
      primaryPatternCode: 'LEFT_RIGHT_ASYMMETRY',
    },
  ],
  page: 0,
  size: 1,
  totalElements: 1,
  totalPages: 1,
};

const result: AnalysisResultResponse = {
  sessionId,
  status: 'COMPLETED',
  algorithmVersion: 'rule-v1.2.0',
  dataQuality: { score: 90, level: 'GOOD', missingFrameRate: 0.002, flags: [] },
  gaitSummary: {
    validStepCount: 20,
    cadence: 108.2,
    leftContactTimeMs: 600,
    rightContactTimeMs: 610,
    symmetryIndex: 1.6,
  },
  pressureDistribution: {
    leftMedialRatio: 0.5,
    leftLateralRatio: 0.5,
    rightMedialRatio: 0.5,
    rightLateralRatio: 0.5,
    leftHeelRatio: 0.35,
    rightHeelRatio: 0.34,
    leftMidfootRatio: 0.25,
    rightMidfootRatio: 0.26,
    leftForefootRatio: 0.4,
    rightForefootRatio: 0.4,
    leftPeakPressure: 88.4,
    rightPeakPressure: 91.2,
    leftMeanCoP: { x: 0.42, y: 0.67 },
    rightMeanCoP: { x: 0.44, y: 0.66 },
  },
  patterns: [],
  recommendations: [
    {
      code: 'ANKLE_STABILITY_BASIC',
      title: '기본 발목 안정화 운동',
      summary: '발목 주변을 편안한 범위에서 움직입니다.',
      durationMinutes: 5,
    },
    {
      code: 'HIP_BALANCE_BASIC',
      title: '기본 고관절 균형 운동',
      summary: '좌우 체중 이동을 천천히 연습합니다.',
      durationMinutes: 8,
    },
  ],
  disclaimer: '본 결과는 의료 진단이 아닙니다.',
  createdAt: '2026-09-01T07:05:00Z',
};

const renderIndex = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/recommendations']}>
        <RecommendationsIndexPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('RecommendationsIndexPage', () => {
  it('최근 완료 결과의 운동 가이드를 상세 링크 타일로 보여준다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue(completedPage);
    const fetchResult = vi
      .spyOn(measurementApi, 'result')
      .mockResolvedValue({ kind: 'completed', data: result });
    renderIndex();

    const tile = await screen.findByRole('link', { name: /기본 발목 안정화 운동/ });
    expect(tile).toHaveAttribute('href', '/recommendations/ANKLE_STABILITY_BASIC');
    expect(screen.getByRole('link', { name: /기본 고관절 균형 운동/ })).toHaveAttribute(
      'href',
      '/recommendations/HIP_BALANCE_BASIC',
    );
    expect(screen.getByRole('link', { name: /결과 보기/ })).toHaveAttribute(
      'href',
      `/measurements/${sessionId}/result`,
    );
    expect(fetchResult).toHaveBeenCalledWith(sessionId);
    expect(screen.getByText('실기기 · 50Hz')).toBeInTheDocument();
    expect(screen.queryByText(/시뮬레이션 세션의 결과입니다/)).toBeNull();
  });

  it('기준 측정이 시뮬레이션 세션이면 배지와 안내로 실기기 결과와 구분한다(DEC-028)', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue({
      ...completedPage,
      items: completedPage.items.map((item) => ({
        ...item,
        sourceType: 'SIMULATED' as const,
        sampleRateHz: 100,
      })),
    });
    vi.spyOn(measurementApi, 'result').mockResolvedValue({ kind: 'completed', data: result });
    renderIndex();

    await screen.findByRole('link', { name: /기본 발목 안정화 운동/ });
    expect(screen.getByText('시뮬레이션 · 100Hz')).toBeInTheDocument();
    const notices = screen.getAllByRole('status');
    expect(notices).toHaveLength(1);
    expect(notices[0]).toHaveTextContent(
      '시뮬레이션 세션의 결과입니다. 실기기 측정이 아니므로 보행 해석에 사용하지 마세요.',
    );
  });

  it('완료된 측정이 없으면 빈 상태 안내를 보여주고 결과 조회를 하지 않는다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue({
      ...completedPage,
      items: [],
      totalElements: 0,
      totalPages: 0,
    });
    const fetchResult = vi.spyOn(measurementApi, 'result');
    renderIndex();

    expect(
      await screen.findByText('측정을 완료하면 결과와 연결된 운동 가이드가 표시됩니다'),
    ).toBeInTheDocument();
    expect(fetchResult).not.toHaveBeenCalled();
  });
});
