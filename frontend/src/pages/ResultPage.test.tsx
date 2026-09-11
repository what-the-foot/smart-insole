import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { deviceApi, measurementApi } from '../api/services';
import type { AnalysisResultResponse, DeviceResponse } from '../api/types';
import { ResultPage } from './ResultPage';

const sessionId = '5803f871-9fca-4a7f-a2c7-9b567a92a6cf';
const leftDeviceId = 'b4b96290-ad73-42d9-ae21-1446f1258861';
const rightDeviceId = '64eb539f-4b48-44f6-bb30-d26861463ca6';

const device = (id: string, footSide: DeviceResponse['footSide']): DeviceResponse => ({
  deviceId: id,
  serialNumber: footSide === 'LEFT' ? 'INSOLE-L-001' : 'INSOLE-R-001',
  displayName: footSide === 'LEFT' ? '왼발 인솔' : '오른발 인솔',
  footSide,
  sensorCount: 8,
  sensorLayoutVersion: 'layout-s01s08-v1',
  activeCalibrationVersion: 'identity-v1',
  firmwareVersion: '0.1.0',
  adcMax: 4095,
  status: 'ACTIVE',
  registeredAt: '2026-09-02T07:00:00Z',
});

const completedResult: AnalysisResultResponse = {
  sessionId,
  status: 'COMPLETED',
  algorithmVersion: 'rule-v1.3.0',
  dataQuality: { score: 92, level: 'GOOD', missingFrameRate: 0.003, flags: [] },
  gaitSummary: {
    validStepCount: 20,
    cadence: 108.2,
    leftContactTimeMs: 642,
    rightContactTimeMs: 608,
    symmetryIndex: 5.3,
    leftStrideTimeMs: 1120,
    rightStrideTimeMs: 1100,
    meanStrideTimeMs: 1110,
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
    rightMeanCoP: { x: 0.44, y: 0.66 },
    leftSensorSharePct: [18, 12, 8, 7, 20, 15, 10, 10],
    rightSensorSharePct: [30, 20, 5, 5, 15, 10, 10, 5],
    leftLoadSharePct: 52,
    rightLoadSharePct: 48,
  },
  patterns: [],
  recommendations: [],
  disclaimer: '본 결과는 의료 진단이 아닙니다.',
  createdAt: '2026-09-02T07:16:03Z',
};

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/measurements/${sessionId}/result`]}>
        <Routes>
          <Route element={<ResultPage />} path="/measurements/:sessionId/result" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('ResultPage', () => {
  it('취소된 측정에서는 비활성 결과 쿼리의 pending 상태 대신 종료 안내를 표시한다', async () => {
    vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'CANCELLED',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      adcMax: 4095,
      memo: null,
      endedAt: '2026-09-02T07:01:00Z',
      createdAt: '2026-09-02T07:00:00Z',
    });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const getLayout = vi.spyOn(deviceApi, 'getLayout');
    const result = vi.spyOn(measurementApi, 'result');
    renderPage();

    expect(await screen.findByRole('heading', { name: '취소된 측정입니다' })).toBeInTheDocument();
    expect(result).not.toHaveBeenCalled();
    expect(getLayout).not.toHaveBeenCalled();
  });

  it('완료된 결과는 세션 기기의 센서 배치를 조회해 세션 평균 히트맵을 양발 모두 그린다', async () => {
    vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'COMPLETED',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 50,
      sourceType: 'DEVICE',
      adcMax: 4095,
      memo: null,
      endedAt: '2026-09-02T07:15:00Z',
      createdAt: '2026-09-02T07:00:00Z',
    });
    vi.spyOn(measurementApi, 'result').mockResolvedValue({
      kind: 'completed',
      data: completedResult,
    });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([
      device(leftDeviceId, 'LEFT'),
      device(rightDeviceId, 'RIGHT'),
    ]);
    const getLayout = vi.spyOn(deviceApi, 'getLayout').mockResolvedValue({
      version: 'layout-s01s08-v1',
      sensorCount: 8,
      points: Array.from({ length: 8 }, (_unused, index) => ({
        label: `S0${index + 1}`,
        index,
        x: 0.3 + (index % 2) * 0.35,
        y: 0.1 + index * 0.1,
        region: 'MIDFOOT' as const,
        medialLateral: 'CENTER' as const,
      })),
    });
    const { container } = renderPage();

    expect(
      await screen.findByRole('img', { name: '왼발 센서 신호 비율 히트맵' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '오른발 센서 신호 비율 히트맵' })).toBeInTheDocument();
    await waitFor(() => expect(container.querySelectorAll('.sensor-point')).toHaveLength(16));
    expect(getLayout).toHaveBeenCalledWith('layout-s01s08-v1');
    expect(screen.getByText('52 : 48')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '데이터 품질 92점, 좋음' })).toBeInTheDocument();
    expect(screen.getByText(/실기기 · 50Hz/)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/최대 압력|CoP|정상/);
  });
});
