export type SupportedSensorCount = 6 | 8;

// 8센서 기본 배치(V6 seed, DEC-026). index = S번호 - 1 = MUX 채널, label S01..S08.
export const DEFAULT_EIGHT_SENSOR_LAYOUT_VERSION = 'layout-s01s08-v1';

// 레거시 배치(layout-v1, layout-v1-6)는 active=false로 조회만 가능하고 등록은 422로 거부된다.
// 6센서 seed는 하드웨어(제외 센서·S08 유무·MUX 매핑) 확정 전까지 만들지 않으므로 등록 폼에서 비활성화한다.
export const SIX_SENSOR_REGISTRATION_NOTE =
  '6센서 배치는 하드웨어 구성이 확정될 때까지 활성 seed가 없어 등록할 수 없습니다.';

export const isSensorCountRegistrable = (sensorCount: SupportedSensorCount): boolean =>
  sensorCount === 8;

export const defaultLayoutVersionForSensorCount = (
  sensorCount: SupportedSensorCount,
): string | null => (sensorCount === 8 ? DEFAULT_EIGHT_SENSOR_LAYOUT_VERSION : null);
