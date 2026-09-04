import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { deviceApi, measurementApi } from '../api/services';
import { AuthProvider } from '../features/auth/AuthContext';
import { LiveMeasurementPage } from './LiveMeasurementPage';

const sessionId = '5803f871-9fca-4a7f-a2c7-9b567a92a6cf';
const leftDeviceId = 'b4b96290-ad73-42d9-ae21-1446f1258861';
const rightDeviceId = '64eb539f-4b48-44f6-bb30-d26861463ca6';

describe('LiveMeasurementPage', () => {
  it('시작 응답을 잃어도 새 세션을 만들지 않고 같은 세션 ID로 재시도한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'CREATED',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      memo: null,
      createdAt: '2026-09-02T07:00:00Z',
    });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([
      {
        deviceId: leftDeviceId,
        serialNumber: 'INSOLE-L-001',
        displayName: '왼발 인솔',
        footSide: 'LEFT',
        sensorCount: 8,
        sensorLayoutVersion: 'layout-v1',
        activeCalibrationVersion: 'identity-v1',
        firmwareVersion: '0.1.0',
        status: 'ACTIVE',
        registeredAt: '2026-09-02T07:00:00Z',
      },
      {
        deviceId: rightDeviceId,
        serialNumber: 'INSOLE-R-001',
        displayName: '오른발 인솔',
        footSide: 'RIGHT',
        sensorCount: 8,
        sensorLayoutVersion: 'layout-v1',
        activeCalibrationVersion: 'identity-v1',
        firmwareVersion: '0.1.0',
        status: 'ACTIVE',
        registeredAt: '2026-09-02T07:00:00Z',
      },
    ]);
    vi.spyOn(deviceApi, 'getLayout').mockResolvedValue({
      version: 'layout-v1',
      sensorCount: 8,
      points: Array.from({ length: 8 }, (_unused, index) => ({
        index,
        x: index / 8,
        y: index / 8,
        region: 'MIDFOOT',
        medialLateral: 'CENTER',
      })),
    });
    const start = vi
      .spyOn(measurementApi, 'start')
      .mockRejectedValueOnce(new Error('응답을 확인하지 못했습니다.'))
      .mockResolvedValue({
        sessionId,
        status: 'MEASURING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '측정 시작' }));
    await user.click(await screen.findByRole('button', { name: '측정 시작 다시 시도' }));

    await waitFor(() => expect(start).toHaveBeenCalledTimes(2));
    expect(start).toHaveBeenNthCalledWith(1, sessionId);
    expect(start).toHaveBeenNthCalledWith(2, sessionId);
  });

  it('기기 목록 조회 실패를 영구 로딩으로 숨기지 않고 재시도한다', async () => {
    const user = userEvent.setup();
    const measurementGet = vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'MEASURING',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      memo: null,
      startedAt: '2026-09-02T07:01:00Z',
      createdAt: '2026-09-02T07:00:00Z',
    });
    const deviceList = vi.spyOn(deviceApi, 'list')
      .mockRejectedValueOnce(new Error('기기 목록을 불러오지 못했습니다.'))
      .mockResolvedValue([]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: '정보를 불러오지 못했어요' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(deviceList).toHaveBeenCalledTimes(2));
    expect(measurementGet).toHaveBeenCalledTimes(2);
  });

  it('종료 응답을 잃어도 서버 상태를 재조회해 결과 화면으로 복구한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(measurementApi, 'get')
      .mockResolvedValueOnce({
        sessionId,
        status: 'MEASURING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      })
      .mockResolvedValue({
        sessionId,
        status: 'PROCESSING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        endedAt: '2026-09-02T07:02:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const complete = vi.spyOn(measurementApi, 'complete').mockRejectedValue(new Error('응답 유실'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
              <Route element={<h1>복구된 결과 화면</h1>} path="/measurements/:sessionId/result" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: /측정 종료/ }));
    expect(await screen.findByRole('heading', { name: '복구된 결과 화면' })).toBeInTheDocument();
    expect(complete).toHaveBeenCalledWith(sessionId);
  });

  it('취소 응답을 잃어도 서버 상태를 재조회해 대시보드로 복구한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(measurementApi, 'get')
      .mockResolvedValueOnce({
        sessionId,
        status: 'MEASURING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      })
      .mockResolvedValue({
        sessionId,
        status: 'CANCELLED',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        endedAt: '2026-09-02T07:02:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const cancel = vi.spyOn(measurementApi, 'cancel').mockRejectedValue(new Error('응답 유실'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
              <Route element={<h1>복구된 대시보드</h1>} path="/dashboard" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '측정 취소' }));
    expect(await screen.findByRole('heading', { name: '복구된 대시보드' })).toBeInTheDocument();
    expect(cancel).toHaveBeenCalledWith(sessionId);
  });
});
