import {
  FIELD_COVERAGE_REF,
  RASTER_HEIGHT,
  RASTER_WIDTH,
  computeField,
  fieldValueAt,
  heatmapX,
  heatmapY,
  renderFieldToDataUrl,
  resetRasterSupportForTests,
  supportsRaster,
  type FieldSample,
} from './heatmapField';

const single: FieldSample[] = [{ x: 0.5, y: 0.5, value: 80 }];

describe('heatmapField geometry', () => {
  it('SVG 원과 같은 좌표식을 쓴다 (x=52+125x, 왼발은 1-x, y=42+310y)', () => {
    expect(heatmapX(0.3, 'RIGHT')).toBe(89.5);
    expect(heatmapX(0.3, 'LEFT')).toBe(139.5);
    expect(heatmapY(0.9)).toBe(321);
  });
});

describe('fieldValueAt (Gaussian Shepard + coverage)', () => {
  it('센서 위치에서는 센서 값과 같고 멀어질수록 단조 감소한다', () => {
    const cx = heatmapX(0.5, 'RIGHT');
    const cy = heatmapY(0.5);
    expect(fieldValueAt(cx, cy, single, 'RIGHT')).toBeCloseTo(80, 5);
    let previous = Number.POSITIVE_INFINITY;
    for (let step = 0; step <= 12; step += 1) {
      const value = fieldValueAt(cx + step * 8, cy, single, 'RIGHT');
      expect(value).toBeLessThanOrEqual(previous);
      previous = value;
    }
  });

  it('센서에서 먼 영역은 커버리지 감소로 가장 낮은 단계(0)로 떨어진다', () => {
    const far = fieldValueAt(5, 5, single, 'RIGHT');
    expect(far).toBeLessThan(0.5);
    expect(fieldValueAt(heatmapX(0.5, 'RIGHT'), heatmapY(1.6), single, 'RIGHT')).toBeLessThan(0.5);
  });

  it('두 센서 사이 중간점은 두 값의 가중 평균이며 0..100을 벗어나지 않는다', () => {
    const pair: FieldSample[] = [
      { x: 0.35, y: 0.5, value: 20 },
      { x: 0.65, y: 0.5, value: 60 },
    ];
    const mid = fieldValueAt(heatmapX(0.5, 'RIGHT'), heatmapY(0.5), pair, 'RIGHT');
    expect(mid).toBeGreaterThan(20);
    expect(mid).toBeLessThan(60);
    expect(
      fieldValueAt(
        heatmapX(0.35, 'RIGHT'),
        heatmapY(0.5),
        [{ x: 0.35, y: 0.5, value: 900 }],
        'RIGHT',
      ),
    ).toBe(100);
    expect(fieldValueAt(0, 0, [], 'RIGHT')).toBe(0);
  });

  it('커버리지 기준(wRef)보다 커널 합이 작으면 비례해 흐려진다', () => {
    const cx = heatmapX(0.5, 'RIGHT');
    const cy = heatmapY(0.5);
    const relaxed = fieldValueAt(cx + 40, cy, single, 'RIGHT', { coverageRef: 1e-9 });
    const strict = fieldValueAt(cx + 40, cy, single, 'RIGHT', { coverageRef: FIELD_COVERAGE_REF });
    expect(strict).toBeLessThanOrEqual(relaxed);
    expect(relaxed).toBeCloseTo(80, 5);
  });

  it('왼발은 외곽선과 같은 방식으로 x만 반전해 오른발 필드의 거울상이 된다', () => {
    const samples: FieldSample[] = [{ x: 0.3, y: 0.4, value: 70 }];
    const columns = 22;
    const rows = 40;
    const right = computeField(samples, 'RIGHT', columns, rows);
    const left = computeField(samples, 'LEFT', columns, rows);
    // 반전축 x=114.5는 셀 폭 10 기준 열 경계가 아니므로 좌표를 직접 비교한다.
    const rightValue = fieldValueAt(
      heatmapX(0.3, 'RIGHT') + 6,
      heatmapY(0.4) + 9,
      samples,
      'RIGHT',
    );
    const leftValue = fieldValueAt(heatmapX(0.3, 'LEFT') - 6, heatmapY(0.4) + 9, samples, 'LEFT');
    expect(leftValue).toBeCloseTo(rightValue, 6);
    expect(right).toHaveLength(columns * rows);
    expect(Math.max(...right)).toBeCloseTo(Math.max(...left), 3);
  });

  it('래스터 크기는 viewBox/4(55x100)이며 모든 셀이 0..100 안에 있다', () => {
    const field = computeField(single, 'RIGHT', RASTER_WIDTH, RASTER_HEIGHT);
    expect(field).toHaveLength(5500);
    for (const value of field) {
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThanOrEqual(100);
    }
  });
});

describe('raster guard (jsdom)', () => {
  it('캔버스 2D 컨텍스트가 없으면 supportsRaster가 false이고 렌더는 null을 돌려준다', () => {
    resetRasterSupportForTests();
    expect(supportsRaster()).toBe(false);
    const canvas = document.createElement('canvas');
    const field = computeField(single, 'RIGHT', 2, 2);
    expect(renderFieldToDataUrl(canvas, field, 2, 2)).toBeNull();
  });
});
