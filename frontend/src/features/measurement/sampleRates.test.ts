import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SAMPLE_RATE_HZ,
  isSampleRateHz,
  resolveDefaultSampleRateHz,
  SAMPLE_RATE_OPTIONS,
} from './sampleRates';

describe('sampleRateHz 선택', () => {
  it('계약 enum 50/100만 허용한다', () => {
    expect(SAMPLE_RATE_OPTIONS).toEqual([50, 100]);
    expect(isSampleRateHz(50)).toBe(true);
    expect(isSampleRateHz(100)).toBe(true);
    expect(isSampleRateHz(60)).toBe(false);
    expect(isSampleRateHz('50')).toBe(false);
  });

  it.each([
    [undefined, 50],
    ['', 50],
    ['50', 50],
    ['100', 100],
    ['60', 50],
    ['abc', 50],
  ])('VITE_DEFAULT_SAMPLE_RATE_HZ=%s 이면 기본값 %s', (setting, expected) => {
    expect(resolveDefaultSampleRateHz(setting)).toBe(expected);
  });

  it('환경변수가 없는 테스트 환경의 기본값은 50이다', () => {
    expect(DEFAULT_SAMPLE_RATE_HZ).toBe(50);
  });
});
