import { describe, expect, it } from 'vitest';
import { defaultLayoutVersionForSensorCount } from './layouts';

describe('기본 센서 배치 선택', () => {
  it('6센서와 8센서를 각각 호환되는 seed layout에 연결한다', () => {
    expect(defaultLayoutVersionForSensorCount(6)).toBe('layout-v1-6');
    expect(defaultLayoutVersionForSensorCount(8)).toBe('layout-v1');
  });
});
