// 히트맵 색상의 단일 출처. 범례(PressureLegend), 센서 원(pressureColor), 래스터 LUT,
// global.css의 --heat-* / --gradient-heat 토큰이 모두 아래 6개 stop을 따라야 한다.
// 값은 세션 adcMax 기준 0..100 상대 신호(또는 센서 신호 비율)이며 임계·판정 의미가 없다.

export interface HeatStop {
  /** 0..100 위치 */
  readonly offset: number;
  readonly hex: string;
}

export const heatStops: readonly HeatStop[] = [
  { offset: 0, hex: '#1E4FD6' },
  { offset: 20, hex: '#22B3C9' },
  { offset: 40, hex: '#3DCB6E' },
  { offset: 60, hex: '#F2D23A' },
  { offset: 80, hex: '#F58B2D' },
  { offset: 100, hex: '#E03A3A' },
];

export type Rgb = readonly [number, number, number];

export const hexToRgb = (hex: string): Rgb => {
  const value = Number.parseInt(hex.replace('#', ''), 16);
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
};

const toHex = (channel: number): string => channel.toString(16).padStart(2, '0');

export const rgbToHex = ([r, g, b]: Rgb): string => `#${toHex(r)}${toHex(g)}${toHex(b)}`;

export const LUT_SIZE = 256;

let cachedLut: Uint8ClampedArray | null = null;

/**
 * 256단계 RGBA LUT(길이 256*4). stop 사이는 sRGB 채널을 선형 보간한다.
 * 인덱스 0 = heatStops[0], 인덱스 255 = heatStops[마지막].
 */
export function lut(): Uint8ClampedArray {
  if (cachedLut) return cachedLut;
  const table = new Uint8ClampedArray(LUT_SIZE * 4);
  const stops = heatStops.map((stop) => ({ offset: stop.offset / 100, rgb: hexToRgb(stop.hex) }));
  for (let i = 0; i < LUT_SIZE; i += 1) {
    const t = i / (LUT_SIZE - 1);
    let upper = stops.findIndex((stop) => stop.offset >= t);
    if (upper < 0) upper = stops.length - 1;
    const lower = Math.max(0, upper - 1);
    const from = stops[lower] ?? stops[0];
    const to = stops[upper] ?? stops[stops.length - 1];
    if (!from || !to) continue;
    const span = to.offset - from.offset;
    const local = span > 0 ? Math.min(1, Math.max(0, (t - from.offset) / span)) : 0;
    const base = i * 4;
    for (let channel = 0; channel < 3; channel += 1) {
      const a = from.rgb[channel] ?? 0;
      const b = to.rgb[channel] ?? 0;
      table[base + channel] = Math.round(a + (b - a) * local);
    }
    table[base + 3] = 255;
  }
  cachedLut = table;
  return table;
}

export const clampSignal = (value: number): number =>
  Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;

/** 0..100 상대 신호 → LUT 인덱스(0..255) */
export const lutIndex = (value: number): number =>
  Math.round((clampSignal(value) / 100) * (LUT_SIZE - 1));

export const lutRgb = (value: number): Rgb => {
  const table = lut();
  const base = lutIndex(value) * 4;
  return [table[base] ?? 0, table[base + 1] ?? 0, table[base + 2] ?? 0];
};

/** 센서 원·범례가 쓰는 색. LUT와 같은 값을 소문자 #rrggbb로 돌려준다. */
export const pressureColor = (value: number): string => rgbToHex(lutRgb(value));

/** 범례 막대용 CSS 그라디언트. 가로는 왼쪽이 낮음, 세로는 아래쪽이 낮음. */
export const heatGradientCss = (orientation: 'horizontal' | 'vertical' = 'horizontal'): string => {
  const direction = orientation === 'vertical' ? '0deg' : '90deg';
  const stops = heatStops.map((stop) => `${stop.hex} ${stop.offset}%`).join(', ');
  return `linear-gradient(${direction}, ${stops})`;
};
