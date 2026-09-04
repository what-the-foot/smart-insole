import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { deviceApi, measurementApi } from '../api/services';
import type { DeviceResponse } from '../api/types';
import { NewMeasurementPage } from './NewMeasurementPage';

const devices: DeviceResponse[] = [
  {
    deviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
    serialNumber: 'INSOLE-L-001',
    displayName: '왼발 인솔',
    footSide: 'LEFT',
    sensorCount: 8,
    sensorLayoutVersion: 'layout-v1',
    activeCalibrationVersion: 'identity-v1',
    firmwareVersion: '0.1.0',
    adcMax: 4095,
    status: 'ACTIVE',
    registeredAt: '2026-09-02T07:00:00Z',
  },
  {
    deviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
    serialNumber: 'INSOLE-R-001',
    displayName: '오른발 인솔',
    footSide: 'RIGHT',
    sensorCount: 8,
    sensorLayoutVersion: 'layout-v1',
    activeCalibrationVersion: 'identity-v1',
    firmwareVersion: '0.1.0',
    adcMax: 4095,
    status: 'ACTIVE',
    registeredAt: '2026-09-02T07:00:00Z',
  },
];

describe('NewMeasurementPage', () => {
  it('세션은 한 번만 생성하고 같은 ID의 live 준비 화면으로 이동한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(deviceApi, 'list').mockResolvedValue(devices);
    const create = vi.spyOn(measurementApi, 'create').mockResolvedValue({
      sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
      status: 'CREATED',
      leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
      rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      adcMax: 4095,
      memo: null,
      createdAt: '2026-09-02T07:00:00Z',
    });
    const start = vi.spyOn(measurementApi, 'start');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/measurements/new']}>
          <Routes>
            <Route element={<NewMeasurementPage />} path="/measurements/new" />
            <Route element={<h1>기존 세션 준비 화면</h1>} path="/measurements/:sessionId/live" />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('radio', { name: /왼발 인솔/ }));
    await user.click(screen.getByRole('radio', { name: /오른발 인솔/ }));
    await user.click(screen.getByRole('button', { name: '준비 완료 및 계속' }));

    expect(await screen.findByRole('heading', { name: '기존 세션 준비 화면' })).toBeInTheDocument();
    expect(create).toHaveBeenCalledTimes(1);
    expect(start).not.toHaveBeenCalled();
  });
});
