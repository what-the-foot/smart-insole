import type { MeasurementHistoryItem } from '../../api/types';
import {
  compareQuality,
  countPatternLevels,
  historyParams,
  loadShareText,
  patternCountSub,
  qualityComparisonText,
  shortDateLabel,
  trendPoints,
} from './dashboardMetrics';

const item = (overrides: Partial<MeasurementHistoryItem>): MeasurementHistoryItem => ({
  sessionId: 'a',
  status: 'COMPLETED',
  leftDeviceId: 'l',
  rightDeviceId: 'r',
  sampleRateHz: 50,
  sourceType: 'DEVICE',
  createdAt: '2026-09-11T07:00:00Z',
  primaryPatternCode: null,
  ...overrides,
});

describe('dashboardMetrics', () => {
  it('7일 창의 from은 기준 시각에서 정확히 7일 전이다', () => {
    expect(historyParams(new Date('2026-09-11T09:00:00Z'))).toEqual({
      page: 0,
      size: 10,
      status: 'COMPLETED',
      from: '2026-09-04T09:00:00.000Z',
    });
  });

  it('좌우 신호 비율은 정수 비율 문자열이고 한쪽이라도 없으면 제공 안 됨이다', () => {
    expect(loadShareText(52.4, 47.6)).toBe('52 : 48');
    expect(loadShareText(null, 48)).toBe('제공 안 됨');
    expect(loadShareText(52, undefined)).toBe('제공 안 됨');
  });

  it('패턴 관찰 단계를 세고 이전 분석만 있으면 미제공 안내를 쓴다', () => {
    const base = { severity: 'INFO' as const, title: 't', message: 'm', evidence: 'e' };
    expect(
      patternCountSub(
        countPatternLevels([
          { ...base, code: 'A', observationLevel: 'REPEATEDLY_OBSERVED' },
          { ...base, code: 'B', observationLevel: 'PARTIALLY_OBSERVED' },
          { ...base, code: 'C', observationLevel: 'PARTIALLY_OBSERVED' },
        ]),
      ),
    ).toBe('반복 관찰 1 · 일부 관찰 2');
    expect(patternCountSub(countPatternLevels([]))).toBe('반복 관찰 0 · 일부 관찰 0');
    expect(
      patternCountSub(countPatternLevels([{ ...base, code: 'L', observationLevel: null }])),
    ).toBe('관찰 단계 미제공(이전 분석)');
  });

  it('품질 점수 비교는 증가/감소/변화 없음만 말한다', () => {
    expect(qualityComparisonText(compareQuality(92, [90, 80]))).toBe(
      '최근 7일 평균 대비 증가 (+7점)',
    );
    expect(qualityComparisonText(compareQuality(70, [80, null, undefined]))).toBe(
      '최근 7일 평균 대비 감소 (-10점)',
    );
    expect(qualityComparisonText(compareQuality(85, [85.04]))).toBe('최근 7일 평균과 변화 없음');
    expect(qualityComparisonText(compareQuality(85, [null]))).toBe(
      '최근 7일에 비교할 다른 측정이 없습니다.',
    );
    expect(qualityComparisonText(compareQuality(85, [80]))).not.toMatch(/개선|악화|정상|양호/);
  });

  it('추세 점은 오래된 순으로 정렬하고 없는 값은 null이다', () => {
    const points = trendPoints(
      [
        item({ sessionId: 'new', createdAt: '2026-09-11T07:00:00Z', symmetryIndex: 5.3 }),
        item({ sessionId: 'old', createdAt: '2026-09-05T07:00:00Z', symmetryIndex: null }),
      ],
      'symmetryIndex',
    );
    expect(points).toEqual([
      { label: shortDateLabel('2026-09-05T07:00:00Z'), value: null },
      { label: shortDateLabel('2026-09-11T07:00:00Z'), value: 5.3 },
    ]);
    expect(shortDateLabel('not-a-date')).toBe('날짜 없음');
  });
});
