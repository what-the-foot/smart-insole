import { batteryLabel, qualityFlagLabel } from './labels';

describe('batteryLabel', () => {
  it.each([
    ['heartbeat 전', {}, '미보정'],
    ['미보정 255→null', { lastBatteryPercent: null, lastBatteryMv: 3900 }, '미보정 · 3900 mV'],
    ['퍼센트와 mV', { lastBatteryPercent: 80, lastBatteryMv: 3900 }, '80% · 3900 mV'],
    ['퍼센트만', { lastBatteryPercent: 47.6, lastBatteryMv: null }, '48%'],
  ])('%s 배터리를 표시한다', (_case, device, expected) => {
    expect(batteryLabel(device)).toBe(expected);
  });
});

describe('qualityFlagLabel', () => {
  it.each([
    ['SENSOR_STUCK_OR_SATURATED', '일부 센서 값이 고정되었거나 측정 범위 끝에 머물렀습니다.'],
    ['OUT_OF_ORDER', '일부 센서 데이터의 순서가 뒤바뀌었습니다.'],
    ['DEVICE_TIME_JUMP', '센서 장치 시간이 불연속적으로 변했습니다.'],
    ['LEFT_DATA_MISSING', '왼발 센서 데이터가 없습니다.'],
    ['RIGHT_DATA_MISSING', '오른발 센서 데이터가 없습니다.'],
    ['LEFT_DATA_INCOMPLETE', '왼발 센서 데이터가 충분하지 않습니다.'],
    ['RIGHT_DATA_INCOMPLETE', '오른발 센서 데이터가 충분하지 않습니다.'],
    ['INSUFFICIENT_DATA', '분석에 사용할 센서 데이터가 충분하지 않습니다.'],
  ])('%s canonical flag를 사용자 문장으로 표시한다', (flag, expected) => {
    expect(qualityFlagLabel(flag)).toBe(expected);
  });
});
