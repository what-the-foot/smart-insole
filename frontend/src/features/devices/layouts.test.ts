import { describe, expect, it } from 'vitest';
import {
  DEFAULT_EIGHT_SENSOR_LAYOUT_VERSION,
  defaultLayoutVersionForSensorCount,
  isSensorCountRegistrable,
} from './layouts';

describe('기본 센서 배치 선택', () => {
  it('8센서는 V6 seed layout-s01s08-v1에 연결한다', () => {
    expect(DEFAULT_EIGHT_SENSOR_LAYOUT_VERSION).toBe('layout-s01s08-v1');
    expect(defaultLayoutVersionForSensorCount(8)).toBe('layout-s01s08-v1');
    expect(isSensorCountRegistrable(8)).toBe(true);
  });

  it('6센서는 활성 seed가 없으므로 기본 배치를 제공하지 않고 등록을 막는다', () => {
    expect(defaultLayoutVersionForSensorCount(6)).toBeNull();
    expect(isSensorCountRegistrable(6)).toBe(false);
  });
});
