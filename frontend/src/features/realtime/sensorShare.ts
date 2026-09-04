// 표시 전용 센서 share(센서/전체합×100). 임계 판단은 백엔드가 담당한다(DEC-025, docs/02 §실시간).
export const sensorSharePercent = (value: number, total: number): number | null =>
  total > 0 ? Math.round((value / total) * 100) : null;

export const sensorTotal = (values: readonly number[]): number =>
  values.reduce((sum, value) => sum + value, 0);
