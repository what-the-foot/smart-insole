import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { deviceApi, measurementApi } from '../api/services';
import type { DeviceResponse, MeasurementSessionResponse } from '../api/types';
import { PREFERENCES_STORAGE_KEY } from '../app/preferences';
import { NewMeasurementPage } from './NewMeasurementPage';

const devices: DeviceResponse[] = [
  {
    deviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
    serialNumber: 'INSOLE-L-001',
    displayName: '왼발 인솔',
    footSide: 'LEFT',
    sensorCount: 8,
    sensorLayoutVersion: 'layout-s01s08-v1',
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
    sensorLayoutVersion: 'layout-s01s08-v1',
    activeCalibrationVersion: 'identity-v1',
    firmwareVersion: '0.1.0',
    adcMax: 4095,
    status: 'ACTIVE',
    registeredAt: '2026-09-02T07:00:00Z',
  },
];

const createdSession: MeasurementSessionResponse = {
  sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
  status: 'CREATED',
  leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
  rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
  sampleRateHz: 50,
  sourceType: 'DEVICE',
  adcMax: 4095,
  memo: null,
  createdAt: '2026-09-02T07:00:00Z',
};

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/measurements/new']}>
        <Routes>
          <Route element={<NewMeasurementPage />} path="/measurements/new" />
          <Route element={<h1>기존 세션 준비 화면</h1>} path="/measurements/:sessionId/live" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('NewMeasurementPage', () => {
  afterEach(() => {
    window.localStorage.removeItem(PREFERENCES_STORAGE_KEY);
  });

  it('설정 페이지에 저장된 기본 전송률(100Hz)로 시작하고, 잘못된 값이면 50Hz로 돌아간다', async () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue(devices);
    window.localStorage.setItem(
      PREFERENCES_STORAGE_KEY,
      JSON.stringify({ defaultSampleRateHz: 100, heatmapMode: 'continuous', reduceMotion: false }),
    );
    const stored = renderPage();
    expect(await screen.findByRole('radio', { name: /^100Hz/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: /^50Hz/ })).not.toBeChecked();
    stored.unmount();

    window.localStorage.setItem(
      PREFERENCES_STORAGE_KEY,
      JSON.stringify({ defaultSampleRateHz: 75 }),
    );
    renderPage();
    expect(await screen.findByRole('radio', { name: /^50Hz/ })).toBeChecked();
  });

  it('세션은 한 번만 생성하고 같은 ID의 live 준비 화면으로 이동한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(deviceApi, 'list').mockResolvedValue(devices);
    const create = vi.spyOn(measurementApi, 'create').mockResolvedValue(createdSession);
    const start = vi.spyOn(measurementApi, 'start');
    renderPage();

    await user.click(await screen.findByRole('radio', { name: /왼발 인솔/ }));
    await user.click(screen.getByRole('radio', { name: /오른발 인솔/ }));
    await user.click(screen.getByRole('button', { name: '준비 완료 및 계속' }));

    expect(await screen.findByRole('heading', { name: '기존 세션 준비 화면' })).toBeInTheDocument();
    expect(create).toHaveBeenCalledTimes(1);
    expect(start).not.toHaveBeenCalled();
  });

  it('기본값은 50Hz·DEVICE이며 sourceType을 자동 판별하지 않는다', async () => {
    const user = userEvent.setup();
    vi.spyOn(deviceApi, 'list').mockResolvedValue(devices);
    const create = vi.spyOn(measurementApi, 'create').mockResolvedValue(createdSession);
    renderPage();

    expect(await screen.findByRole('radio', { name: /^50Hz/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: /^100Hz/ })).not.toBeChecked();
    await user.click(screen.getByRole('radio', { name: /왼발 인솔/ }));
    await user.click(screen.getByRole('radio', { name: /오른발 인솔/ }));
    await user.click(screen.getByRole('button', { name: '준비 완료 및 계속' }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0]?.[0]).toEqual({
      leftDeviceId: devices[0]?.deviceId,
      rightDeviceId: devices[1]?.deviceId,
      sampleRateHz: 50,
      sourceType: 'DEVICE',
      memo: null,
    });
  });

  it('100Hz와 개발 모드 시뮬레이션 세션을 선택하면 sampleRateHz 100·SIMULATED를 명시해 보낸다', async () => {
    const user = userEvent.setup();
    vi.spyOn(deviceApi, 'list').mockResolvedValue(devices);
    const create = vi.spyOn(measurementApi, 'create').mockResolvedValue({
      ...createdSession,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
    });
    renderPage();

    await user.click(await screen.findByRole('radio', { name: /왼발 인솔/ }));
    await user.click(screen.getByRole('radio', { name: /오른발 인솔/ }));
    await user.click(screen.getByRole('radio', { name: /^100Hz/ }));
    // vitest는 import.meta.env.DEV=true이므로 개발 모드 체크박스가 노출된다.
    await user.click(screen.getByRole('checkbox', { name: /시뮬레이션 세션/ }));
    await user.type(screen.getByRole('textbox', { name: /측정 메모/ }), 'mock receiver E2E');
    await user.click(screen.getByRole('button', { name: '준비 완료 및 계속' }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0]?.[0]).toEqual({
      leftDeviceId: devices[0]?.deviceId,
      rightDeviceId: devices[1]?.deviceId,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      memo: 'mock receiver E2E',
    });
  });

  it('단계 표시는 인솔 선택 → 전송률 → 시작 순이며 양발을 고르면 2단계로 넘어가고 정지 자세 안내를 보여준다', async () => {
    const user = userEvent.setup();
    vi.spyOn(deviceApi, 'list').mockResolvedValue(devices);
    renderPage();

    const stepper = await screen.findByRole('list', { name: '측정 준비 단계' });
    const steps = within(stepper).getAllByRole('listitem');
    // 보이는 숫자(aria-hidden)와 보조기기용 접두사(sr-only)가 함께 있으므로 각각 확인한다.
    expect(steps.map((step) => step.querySelector('.sr-only')?.textContent)).toEqual([
      '1단계: ',
      '2단계: ',
      '3단계: ',
    ]);
    expect(steps[0]).toHaveTextContent(/인솔 선택$/);
    expect(steps[1]).toHaveTextContent(/전송률$/);
    expect(steps[2]).toHaveTextContent(/시작$/);
    expect(steps[0]).toHaveAttribute('aria-current', 'step');
    expect(steps[1]).not.toHaveAttribute('aria-current');

    await user.click(await screen.findByRole('radio', { name: /왼발 인솔/ }));
    expect(steps[0]).toHaveAttribute('aria-current', 'step');
    await user.click(screen.getByRole('radio', { name: /오른발 인솔/ }));
    expect(steps[0]).not.toHaveAttribute('aria-current');
    expect(steps[0]).toHaveClass('stepper__done');
    expect(steps[0]?.querySelector('.sr-only')).toHaveTextContent('1단계 완료:');
    expect(steps[1]).toHaveAttribute('aria-current', 'step');

    expect(screen.getByText('측정을 시작하면 2초간 가만히 서 있어 주세요.')).toBeInTheDocument();
    expect(
      screen.getByText(/정강이 IMU 기준 자세는 측정 시작 직후 정지 구간에서 자동으로 잡힙니다\./),
    ).toBeInTheDocument();
  });
});
