import type { SensorLayoutResponse } from './types';

// 계약(contracts/openapi.yaml, SensorLayoutResponse/SensorPoint)의 exact-key 배열.
// 계약이 바뀌면 `npm run api:generate` 후 이 배열과 runtimeValidation.test.ts를 함께 갱신한다.
const layoutKeys = ['version', 'sensorCount', 'points'] as const;
const requiredPointKeys = ['index', 'x', 'y', 'region', 'medialLateral'] as const;
// SensorPoint.label(1.1.0): 선택·nullable. 펌웨어 센서 라벨(S01..S08), 레거시 레이아웃은 null 또는 생략.
const optionalPointKeys = ['label'] as const;
const pointKeys = [...requiredPointKeys, ...optionalPointKeys] as const;
const regions = ['HEEL', 'MIDFOOT', 'FOREFOOT', 'TOE'] as const;
const horizontalRegions = ['MEDIAL', 'CENTER', 'LATERAL'] as const;
const SENSOR_LABEL_MAX_LENGTH = 16;
const rfc3339DateTimePattern =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?([Zz]|[+-]\d{2}:\d{2})$/;

export const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const hasExactKeys = (
  value: Record<string, unknown>,
  expectedKeys: readonly string[],
): boolean => {
  const actualKeys = Object.keys(value);
  return (
    actualKeys.length === expectedKeys.length &&
    expectedKeys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
  );
};

const hasOnlyKnownKeys = (
  value: Record<string, unknown>,
  requiredKeys: readonly string[],
  optionalKeys: readonly string[],
): boolean =>
  requiredKeys.every((key) => Object.prototype.hasOwnProperty.call(value, key)) &&
  Object.keys(value).every((key) => requiredKeys.includes(key) || optionalKeys.includes(key));

export const isRfc3339DateTime = (value: unknown): value is string => {
  if (typeof value !== 'string') return false;
  const match = rfc3339DateTimePattern.exec(value);
  if (!match) return false;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const timezone = match[7];
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysByMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  const daysInMonth = daysByMonth[month - 1];

  if (
    daysInMonth === undefined ||
    day < 1 ||
    day > daysInMonth ||
    hour > 23 ||
    minute > 59 ||
    second > 60
  ) {
    return false;
  }
  if (timezone?.toUpperCase() === 'Z') return true;
  return (
    timezone !== undefined &&
    Number(timezone.slice(1, 3)) <= 23 &&
    Number(timezone.slice(4, 6)) <= 59
  );
};

const isUnitNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1;

const isSensorLabel = (value: unknown): value is string | null | undefined =>
  value === undefined ||
  value === null ||
  (typeof value === 'string' && value.length > 0 && value.length <= SENSOR_LABEL_MAX_LENGTH);

const isSensorPoint = (value: unknown, expectedIndex: number): boolean =>
  isRecord(value) &&
  hasOnlyKnownKeys(value, requiredPointKeys, optionalPointKeys) &&
  value.index === expectedIndex &&
  isUnitNumber(value.x) &&
  isUnitNumber(value.y) &&
  typeof value.region === 'string' &&
  regions.includes(value.region as (typeof regions)[number]) &&
  typeof value.medialLateral === 'string' &&
  horizontalRegions.includes(value.medialLateral as (typeof horizontalRegions)[number]) &&
  isSensorLabel(value.label);

export const sensorPointKeys: readonly string[] = pointKeys;

export const parseSensorLayoutValue = (value: unknown): SensorLayoutResponse => {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, layoutKeys) ||
    typeof value.version !== 'string' ||
    (value.sensorCount !== 6 && value.sensorCount !== 8) ||
    !Array.isArray(value.points) ||
    value.points.length !== value.sensorCount ||
    !value.points.every((point, index) => isSensorPoint(point, index))
  ) {
    throw new Error('센서 레이아웃 형식이 올바르지 않습니다.');
  }
  return value as SensorLayoutResponse;
};
