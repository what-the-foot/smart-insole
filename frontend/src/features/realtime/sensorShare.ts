// 표시 전용 센서 share(센서/전체합×100). 임계 판단은 백엔드가 담당한다(DEC-025, docs/02 §실시간).

/** rule-v1.2.0 이전 결과(sensorSharePct null)의 세션 평균 히트맵 안내 문구 */
export const LEGACY_SHARE_MESSAGE = '이전 분석 결과에는 센서 비율이 없습니다.';
export const sensorSharePercent = (value: number, total: number): number | null =>
  total > 0 ? Math.round((value / total) * 100) : null;

export const sensorTotal = (values: readonly number[]): number =>
  values.reduce((sum, value) => sum + value, 0);

/**
 * 양발 share 배열의 공통 최대값. 세션 평균 히트맵에서 두 발이 같은 색 축을 쓰도록 호출자가 넘긴다.
 * 유효한 값이 없으면 0.
 */
export const sensorShareMax = (
  ...arrays: readonly (readonly number[] | null | undefined)[]
): number => {
  let max = 0;
  for (const values of arrays) {
    if (!values) continue;
    for (const value of values) {
      if (Number.isFinite(value) && value > max) max = value;
    }
  }
  return max;
};

/** 값이 큰 순서로 상위 count개 (index, value). 동률은 index 순. */
export const topSensors = (
  values: readonly number[],
  count = 2,
): readonly { index: number; value: number }[] =>
  values
    .map((value, index) => ({ index, value }))
    .filter((entry) => Number.isFinite(entry.value))
    .sort((a, b) => b.value - a.value || a.index - b.index)
    .slice(0, count);
