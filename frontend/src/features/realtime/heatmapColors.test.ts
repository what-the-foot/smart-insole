import {
  LUT_SIZE,
  heatGradientCss,
  heatStops,
  hexToRgb,
  lut,
  lutIndex,
  lutRgb,
  pressureColor,
  rgbToHex,
} from './heatmapColors';

describe('heatmapColors', () => {
  it('6개 stop이 브랜드 jet 팔레트와 같고 0..100을 등간격으로 덮는다', () => {
    expect(heatStops.map((stop) => stop.hex)).toEqual([
      '#1E4FD6',
      '#22B3C9',
      '#3DCB6E',
      '#F2D23A',
      '#F58B2D',
      '#E03A3A',
    ]);
    expect(heatStops.map((stop) => stop.offset)).toEqual([0, 20, 40, 60, 80, 100]);
  });

  it('LUT는 256개 RGBA 항목이며 양 끝이 첫/마지막 stop과 같고 alpha는 255다', () => {
    const table = lut();
    expect(table).toHaveLength(LUT_SIZE * 4);
    expect(Array.from(table.slice(0, 4))).toEqual([...hexToRgb('#1E4FD6'), 255]);
    expect(Array.from(table.slice((LUT_SIZE - 1) * 4))).toEqual([...hexToRgb('#E03A3A'), 255]);
    for (let i = 0; i < LUT_SIZE; i += 1) expect(table[i * 4 + 3]).toBe(255);
    expect(lut()).toBe(table);
  });

  it('중간 stop 위치(20·40·60·80)에서 LUT가 해당 stop 색과 1단계 이내로 일치한다', () => {
    for (const stop of heatStops) {
      const expected = hexToRgb(stop.hex);
      const actual = lutRgb(stop.offset);
      actual.forEach((channel, index) => {
        expect(Math.abs(channel - (expected[index] ?? 0))).toBeLessThanOrEqual(2);
      });
    }
  });

  it('pressureColor는 0..100을 clamp하고 LUT 끝점 색을 소문자 hex로 돌려준다', () => {
    expect(pressureColor(0)).toBe('#1e4fd6');
    expect(pressureColor(100)).toBe('#e03a3a');
    expect(pressureColor(-40)).toBe(pressureColor(0));
    expect(pressureColor(400)).toBe(pressureColor(100));
    expect(pressureColor(Number.NaN)).toBe(pressureColor(0));
    expect(lutIndex(50)).toBe(128);
    expect(rgbToHex(hexToRgb('#3DCB6E'))).toBe('#3dcb6e');
  });

  it('LUT 전체에서 색상(hue)이 파랑(224°)에서 빨강(0°)으로 단조 감소한다', () => {
    const hue = ([r8, g8, b8]: readonly [number, number, number]): number => {
      const r = r8 / 255;
      const g = g8 / 255;
      const b = b8 / 255;
      const max = Math.max(r, g, b);
      const min = Math.min(r, g, b);
      const delta = max - min;
      if (delta === 0) return 0;
      let h: number;
      if (max === r) h = ((g - b) / delta) % 6;
      else if (max === g) h = (b - r) / delta + 2;
      else h = (r - g) / delta + 4;
      h *= 60;
      return h < 0 ? h + 360 : h;
    };
    expect(hue(lutRgb(0))).toBeCloseTo(224, 0);
    expect(hue(lutRgb(100))).toBeCloseTo(0, 0);
    let previous = Number.POSITIVE_INFINITY;
    for (let value = 0; value <= 100; value += 0.5) {
      const current = hue(lutRgb(value));
      expect(current).toBeLessThanOrEqual(previous + 0.01);
      previous = current;
    }
  });

  it('범례 그라디언트 문자열이 같은 stop을 쓰고 세로 방향은 아래가 낮음이다', () => {
    expect(heatGradientCss()).toBe(
      'linear-gradient(90deg, #1E4FD6 0%, #22B3C9 20%, #3DCB6E 40%, #F2D23A 60%, #F58B2D 80%, #E03A3A 100%)',
    );
    expect(heatGradientCss('vertical').startsWith('linear-gradient(0deg, #1E4FD6 0%')).toBe(true);
  });
});
