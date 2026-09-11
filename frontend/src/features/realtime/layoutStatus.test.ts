import type { DeviceResponse } from '../../api/types';
import { sensorLayoutStatus, type LayoutStatusInput } from './layoutStatus';

const device = { deviceId: 'd1', sensorLayoutVersion: 'layout-s01s08-v1' } as DeviceResponse;
const fetching: LayoutStatusInput['layout'] = {
  isPending: true,
  isError: false,
  fetchStatus: 'fetching',
};
const idle: LayoutStatusInput['layout'] = { isPending: true, isError: false, fetchStatus: 'idle' };
const ready = { isPending: false, isError: false };

describe('sensorLayoutStatus', () => {
  it('기기 목록이나 배치 조회가 실패하면 error다', () => {
    expect(
      sensorLayoutStatus({
        layout: fetching,
        devices: { isPending: false, isError: true },
        device,
      }),
    ).toBe('error');
    expect(sensorLayoutStatus({ layout: { ...idle, isError: true }, devices: ready, device })).toBe(
      'error',
    );
  });

  it('기기 목록을 아직 받지 못했거나 배치를 실제로 조회 중이면 loading이다', () => {
    expect(
      sensorLayoutStatus({ layout: idle, devices: { isPending: true, isError: false }, device }),
    ).toBe('loading');
    expect(sensorLayoutStatus({ layout: fetching, devices: ready, device })).toBe('loading');
  });

  it('세션 기기가 목록에 없어 배치 조회가 시작되지 않으면 unavailable이다', () => {
    expect(sensorLayoutStatus({ layout: idle, devices: ready, device: undefined })).toBe(
      'unavailable',
    );
    expect(sensorLayoutStatus({ layout: idle, devices: ready, device })).toBe('unavailable');
  });
});
