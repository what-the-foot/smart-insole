import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DeviceResponse } from '../api/types';
import { FootDeviceSelector } from './FootDeviceSelector';

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
    sensorCount: 6,
    sensorLayoutVersion: 'layout-v1-6',
    activeCalibrationVersion: 'identity-v1',
    firmwareVersion: '0.1.0',
    adcMax: 4095,
    status: 'ACTIVE',
    registeredAt: '2026-09-02T07:00:00Z',
  },
];

describe('FootDeviceSelector', () => {
  it('요청한 발 방향의 기기만 보여주고 키보드로 선택할 수 있다', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn<(deviceId: string) => void>();
    render(<FootDeviceSelector devices={devices} onChange={onChange} selectedId="" side="LEFT" />);

    expect(screen.queryByText('오른발 인솔')).not.toBeInTheDocument();
    const option = screen.getByRole('radio', { name: /왼발 인솔/ });
    await user.click(option);
    expect(onChange).toHaveBeenCalledWith(devices[0]?.deviceId);
  });

  it('활성 보정이 없는 기기는 선택을 막고 이유를 안내한다', () => {
    const leftDevice = devices[0];
    if (!leftDevice) throw new Error('왼발 테스트 기기가 필요합니다.');
    const withoutCalibration: DeviceResponse = {
      ...leftDevice,
      activeCalibrationVersion: null,
    };
    render(
      <FootDeviceSelector
        devices={[withoutCalibration]}
        onChange={vi.fn()}
        selectedId=""
        side="LEFT"
      />,
    );

    expect(screen.getByRole('radio', { name: /왼발 인솔/ })).toBeDisabled();
    expect(screen.getByText('활성 보정이 없는 인솔은 선택할 수 없습니다.')).toBeInTheDocument();
  });
});
