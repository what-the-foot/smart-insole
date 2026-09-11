// 히트맵 표면(래스터) 계산. 백엔드가 준 센서 값만 시각화하며 영역 비율·임계 판단은 하지 않는다.
// SVG 기하(viewBox 0 0 220 400, 센서 박스 125x310 @ (52,42))는 FootPressureHeatmap.test가 고정한다.
import type { FootSide } from '../../api/types';
import { clampSignal, lut, lutIndex } from './heatmapColors';

export const VIEWBOX_WIDTH = 220;
export const VIEWBOX_HEIGHT = 400;
export const SENSOR_BOX = { x: 52, y: 42, width: 125, height: 310 } as const;

/** 발 로컬 x(0=내측..1=외측) → viewBox x. 왼발은 착용자 시점으로 좌우 반전한다. */
export const heatmapX = (value: number, side: FootSide): number =>
  SENSOR_BOX.x + (side === 'LEFT' ? 1 - value : value) * SENSOR_BOX.width;

/** 발 로컬 y(0=발끝..1=뒤꿈치) → viewBox y. 반전하지 않는다. */
export const heatmapY = (value: number): number => SENSOR_BOX.y + value * SENSOR_BOX.height;

export const FOOT_OUTLINE_PATH =
  'M109 371c-36 0-58-30-57-69 1-30 17-55 24-77 8-24 3-48 1-74-2-37 6-81 30-108 17-19 48-24 62-5 13 18 6 49-3 68-9 20-13 38-7 63 7 30 20 62 18 99-3 59-26 103-68 103Z';
export const LEFT_MIRROR_TRANSFORM = 'translate(229 0) scale(-1 1)';

/** 가우시안 커널 폭(센서 박스 정규화 단위, x 기준 약 20px) */
export const FIELD_SIGMA = 0.16;
/** 이 값보다 커널 합이 작으면 색이 가장 낮은 단계로 흐려진다(멀리 있는 센서를 발 전체에 번지지 않게) */
export const FIELD_COVERAGE_REF = 0.35;

export const RASTER_WIDTH = 55;
export const RASTER_HEIGHT = 100;
export const FALLBACK_COLUMNS = 22;
export const FALLBACK_ROWS = 40;

export interface FieldSample {
  /** 발 로컬 좌표 */
  readonly x: number;
  readonly y: number;
  /** 0..100 상대 신호 */
  readonly value: number;
}

export interface FieldOptions {
  readonly sigma?: number;
  readonly coverageRef?: number;
}

/** viewBox 좌표 한 점의 보간 값(0..100). 커버리지가 낮으면 0으로 떨어진다. */
export function fieldValueAt(
  vx: number,
  vy: number,
  samples: readonly FieldSample[],
  side: FootSide,
  options: FieldOptions = {},
): number {
  const sigma = options.sigma ?? FIELD_SIGMA;
  const coverageRef = options.coverageRef ?? FIELD_COVERAGE_REF;
  const denominator = 2 * sigma * sigma;
  let weightSum = 0;
  let weighted = 0;
  for (const sample of samples) {
    const dx = (vx - heatmapX(sample.x, side)) / SENSOR_BOX.width;
    const dy = (vy - heatmapY(sample.y)) / SENSOR_BOX.height;
    const weight = Math.exp(-(dx * dx + dy * dy) / denominator);
    weightSum += weight;
    weighted += weight * clampSignal(sample.value);
  }
  if (weightSum <= 0) return 0;
  const coverage = Math.min(1, weightSum / coverageRef);
  return clampSignal((weighted / weightSum) * coverage);
}

/**
 * 전체 viewBox를 columns x rows 격자로 나눠 각 셀 중심의 값을 계산한다(행 우선).
 */
export function computeField(
  samples: readonly FieldSample[],
  side: FootSide,
  columns: number,
  rows: number,
  options: FieldOptions = {},
): Float32Array {
  const field = new Float32Array(columns * rows);
  const cellWidth = VIEWBOX_WIDTH / columns;
  const cellHeight = VIEWBOX_HEIGHT / rows;
  for (let row = 0; row < rows; row += 1) {
    const vy = (row + 0.5) * cellHeight;
    for (let column = 0; column < columns; column += 1) {
      const vx = (column + 0.5) * cellWidth;
      field[row * columns + column] = fieldValueAt(vx, vy, samples, side, options);
    }
  }
  return field;
}

let rasterSupport: boolean | null = null;

/** jsdom(getContext 미구현)·캔버스 없는 환경에서는 false. 한 번만 평가한다. */
export function supportsRaster(): boolean {
  if (rasterSupport !== null) return rasterSupport;
  try {
    rasterSupport =
      typeof document !== 'undefined' &&
      typeof HTMLCanvasElement !== 'undefined' &&
      typeof CanvasRenderingContext2D !== 'undefined' &&
      document.createElement('canvas').getContext('2d') !== null;
  } catch {
    rasterSupport = false;
  }
  return rasterSupport;
}

/** 테스트용: 지원 여부 캐시를 초기화한다. */
export const resetRasterSupportForTests = (): void => {
  rasterSupport = null;
};

/** 필드를 캔버스에 칠하고 PNG data URL을 돌려준다. 캔버스를 쓸 수 없으면 null. */
export function renderFieldToDataUrl(
  canvas: HTMLCanvasElement,
  field: Float32Array,
  columns: number,
  rows: number,
): string | null {
  if (!supportsRaster()) return null;
  let context: CanvasRenderingContext2D | null = null;
  try {
    if (canvas.width !== columns) canvas.width = columns;
    if (canvas.height !== rows) canvas.height = rows;
    context = canvas.getContext('2d');
  } catch {
    return null;
  }
  if (!context) return null;
  const table = lut();
  const image = context.createImageData(columns, rows);
  const pixels = image.data;
  for (let i = 0; i < field.length; i += 1) {
    const base = lutIndex(field[i] ?? 0) * 4;
    const out = i * 4;
    pixels[out] = table[base] ?? 0;
    pixels[out + 1] = table[base + 1] ?? 0;
    pixels[out + 2] = table[base + 2] ?? 0;
    pixels[out + 3] = 255;
  }
  context.putImageData(image, 0, 0);
  try {
    return canvas.toDataURL('image/png');
  } catch {
    return null;
  }
}
