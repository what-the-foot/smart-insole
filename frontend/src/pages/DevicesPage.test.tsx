import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { deviceApi } from '../api/services';
import type { DeviceResponse } from '../api/types';
import { DevicesPage } from './DevicesPage';

const registered: DeviceResponse = {
  deviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
  serialNumber: 'SMART-INSOLE-L-12345678',
  displayName: '왼발 인솔',
  footSide: 'LEFT',
  sensorCount: 8,
  sensorLayoutVersion: 'layout-s01s08-v1',
  firmwareVersion: '0.2.0',
  adcMax: 4095,
  activeCalibrationVersion: 'identity-v1',
  status: 'ACTIVE',
  lastSeenAt: '2026-09-04T01:00:00Z',
  lastBatteryPercent: null,
  lastBatteryMv: 3900,
  registeredAt: '2026-09-02T07:00:00Z',
};

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <DevicesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('DevicesPage', () => {
  it('8센서 기본 배치 layout-s01s08-v1과 adcMax 4095로 등록하고 6센서 옵션은 비활성화한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const register = vi.spyOn(deviceApi, 'register').mockResolvedValue(registered);
    renderPage();

    await user.click(await screen.findByRole('button', { name: '인솔 등록' }));
    const sensorCount = screen.getByRole('combobox', { name: /센서 수/ });
    expect(within(sensorCount).getByRole('option', { name: '6개 (등록 불가)' })).toBeDisabled();
    expect(screen.getByText(/6센서 배치는 하드웨어 구성이 확정될 때까지/)).toBeInTheDocument();
    expect(screen.getByLabelText(/센서 배치 버전/)).toHaveValue('layout-s01s08-v1');
    expect(screen.getByLabelText(/ADC 최댓값/)).toHaveValue('4095');

    await user.type(screen.getByLabelText('표시 이름'), '왼발 인솔');
    await user.type(screen.getByLabelText('시리얼 번호'), 'SMART-INSOLE-L-12345678');
    await user.click(screen.getByRole('button', { name: '등록 완료' }));

    await waitFor(() => expect(register).toHaveBeenCalledTimes(1));
    expect(register.mock.calls[0]?.[0]).toEqual({
      serialNumber: 'SMART-INSOLE-L-12345678',
      displayName: '왼발 인솔',
      footSide: 'LEFT',
      sensorCount: 8,
      sensorLayoutVersion: 'layout-s01s08-v1',
      firmwareVersion: '0.2.0',
      adcMax: 4095,
    });
  });

  it('기기 카드에 adcMax와 마지막 배터리를 표시하고 미보정(null)은 문구로 구분한다', async () => {
    vi.spyOn(deviceApi, 'list').mockResolvedValue([
      registered,
      {
        ...registered,
        deviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
        serialNumber: 'INSOLE-R-LEGACY',
        displayName: '레거시 오른발',
        footSide: 'RIGHT',
        sensorLayoutVersion: 'layout-v1',
        adcMax: 65535,
        lastBatteryPercent: 80,
        lastBatteryMv: 3900,
      },
    ]);
    renderPage();

    const [currentCard, legacyCard] = await screen.findAllByRole('article');
    if (!currentCard || !legacyCard) throw new Error('두 기기 카드가 렌더링되어야 합니다.');
    expect(within(currentCard).getByText('4095')).toBeInTheDocument();
    expect(within(currentCard).getByText('미보정 · 3900 mV')).toBeInTheDocument();
    expect(within(legacyCard).getByText('80% · 3900 mV')).toBeInTheDocument();
    expect(within(legacyCard).getByText(/레거시 ADC 스케일\(65535\)/)).toBeInTheDocument();
  });
});
