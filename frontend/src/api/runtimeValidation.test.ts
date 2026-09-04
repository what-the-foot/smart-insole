import { isRfc3339DateTime, parseSensorLayoutValue, sensorPointKeys } from './runtimeValidation';

const legacyLayout = {
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

const labeledLayout = {
  version: 'layout-s01s08-v1',
  sensorCount: 8,
  points: Array.from({ length: 8 }, (_unused, index) => ({
    label: `S0${index + 1}`,
    index,
    x: index / 8,
    y: index / 8,
    region: 'MIDFOOT',
    medialLateral: 'CENTER',
  })),
};

describe('REST 응답 런타임 검증', () => {
  it('label이 없는 레거시 6센서 레이아웃을 허용한다', () => {
    expect(parseSensorLayoutValue(legacyLayout).sensorCount).toBe(6);
  });

  it('label S01..S08이 있는 layout-s01s08-v1을 허용한다', () => {
    const parsed = parseSensorLayoutValue(labeledLayout);
    expect(parsed.sensorCount).toBe(8);
    expect(parsed.points.map((point) => point.label)).toEqual([
      'S01',
      'S02',
      'S03',
      'S04',
      'S05',
      'S06',
      'S07',
      'S08',
    ]);
  });

  it('label이 null인 센서 점을 허용한다', () => {
    const withNullLabel = {
      ...legacyLayout,
      points: legacyLayout.points.map((point) => ({ ...point, label: null })),
    };
    expect(parseSensorLayoutValue(withNullLabel).points[0]?.label).toBeNull();
  });

  it('SensorPoint exact-key 배열이 계약 1.1.0의 키와 같다', () => {
    expect([...sensorPointKeys].sort()).toEqual(
      ['index', 'label', 'medialLateral', 'region', 'x', 'y'].sort(),
    );
  });

  it.each([
    ['7개 센서', { ...legacyLayout, sensorCount: 7 }],
    ['선언 수와 배열 길이 불일치', { ...legacyLayout, sensorCount: 8 }],
    [
      '순서가 어긋난 센서 인덱스',
      {
        ...legacyLayout,
        points: legacyLayout.points.map((point, index) => ({ ...point, index: index + 1 })),
      },
    ],
    ['추가 속성', { ...legacyLayout, unexpected: true }],
    [
      '센서 점의 추가 속성',
      {
        ...labeledLayout,
        points: labeledLayout.points.map((point) => ({ ...point, muxChannel: point.index })),
      },
    ],
    [
      '문자열이 아닌 label',
      { ...labeledLayout, points: labeledLayout.points.map((point) => ({ ...point, label: 1 })) },
    ],
    [
      '16자를 넘는 label',
      {
        ...labeledLayout,
        points: labeledLayout.points.map((point) => ({ ...point, label: 'S'.repeat(17) })),
      },
    ],
    [
      '필수 키가 빠진 센서 점',
      {
        ...labeledLayout,
        points: labeledLayout.points.map((point) =>
          Object.fromEntries(Object.entries(point).filter(([key]) => key !== 'region')),
        ),
      },
    ],
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
