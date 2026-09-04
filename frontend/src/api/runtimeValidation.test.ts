import { isRfc3339DateTime, parseSensorLayoutValue } from './runtimeValidation';

const validLayout = {
  version: 'layout-v1-6',
  sensorCount: 6,
  points: Array.from({ length: 6 }, (_unused, index) => ({
    index,
    x: index / 6,
    y: index / 6,
    region: 'MIDFOOT',
    medialLateral: 'CENTER',
  })),
};

describe('REST 응답 런타임 검증', () => {
  it('6센서 레이아웃을 허용한다', () => {
    expect(parseSensorLayoutValue(validLayout).sensorCount).toBe(6);
  });

  it.each([
    ['7개 센서', { ...validLayout, sensorCount: 7 }],
    ['선언 수와 배열 길이 불일치', { ...validLayout, sensorCount: 8 }],
    [
      '순서가 어긋난 센서 인덱스',
      { ...validLayout, points: validLayout.points.map((point, index) => ({ ...point, index: index + 1 })) },
    ],
    ['추가 속성', { ...validLayout, unexpected: true }],
  ])('%s 레이아웃을 거부한다', (_case, malformed) => {
    expect(() => parseSensorLayoutValue(malformed)).toThrow('센서 레이아웃');
  });

  it.each([
    ['2026-09-02T07:30:00Z', true],
    ['2026-09-02T16:30:00.123+09:00', true],
    ['2026-02-30T07:30:00Z', false],
    ['2026-09-02', false],
  ])('RFC 3339 date-time %s 판별 결과가 %s다', (value, expected) => {
    expect(isRfc3339DateTime(value)).toBe(expected);
  });
});
