export type SupportedSensorCount = 6 | 8;

export const defaultLayoutVersionForSensorCount = (sensorCount: SupportedSensorCount): string =>
  sensorCount === 6 ? 'layout-v1-6' : 'layout-v1';
