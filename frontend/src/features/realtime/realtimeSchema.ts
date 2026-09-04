import type {
  ContactState,
  FootRealtimeData,
  QualityLevel,
  RealtimePressureMessage,
} from '../../api/types';

// 계약(contracts/openapi.yaml, RealtimePressureMessage 1.0)의 exact-key 배열.
// 계약이 바뀌면 `npm run api:generate` 후 이 배열과 realtimeSchema.test.ts를 함께 갱신한다.
// openapi 1.1.0에서 실시간 메시지 스키마는 바뀌지 않았다(품질 flags는 자유 문자열).
const qualityLevels: readonly QualityLevel[] = ['GOOD', 'ACCEPTABLE', 'POOR'];
const contactStates: readonly ContactState[] = ['NO_CONTACT', 'CONTACT', 'UNKNOWN'];
const rootKeys = [
  'schemaVersion',
  'sessionId',
  'serverTime',
  'elapsedTimeMs',
  'status',
  'left',
  'right',
  'quality',
] as const;
const footKeys = [
  'connected',
  'lastSequence',
  'deviceTimeMs',
  'sensorValues',
  'totalPressure',
  'cop',
  'contactState',
  'lastReceivedAt',
] as const;
const copKeys = ['x', 'y'] as const;
const qualityKeys = ['score', 'level', 'flags'] as const;

const uuidPattern = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const rfc3339DateTimePattern =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?([Zz]|[+-]\d{2}:\d{2})$/;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const hasExactKeys = (value: Record<string, unknown>, expectedKeys: readonly string[]): boolean => {
  const actualKeys = Object.keys(value);
  return (
    actualKeys.length === expectedKeys.length &&
    expectedKeys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
  );
};

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);

const isIntegerAtLeastZero = (value: unknown): value is number =>
  isFiniteNumber(value) && Number.isInteger(value) && value >= 0;

const isUuid = (value: unknown): value is string =>
  typeof value === 'string' && uuidPattern.test(value);

const isRfc3339DateTime = (value: unknown): value is string => {
  if (typeof value !== 'string') return false;
  const match = rfc3339DateTimePattern.exec(value);
  if (!match) return false;

  const [, yearText, monthText, dayText, hourText, minuteText, secondText, timezone] = match;
  if (
    yearText === undefined ||
    monthText === undefined ||
    dayText === undefined ||
    hourText === undefined ||
    minuteText === undefined ||
    secondText === undefined ||
    timezone === undefined
  ) {
    return false;
  }
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
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

  if (timezone.toUpperCase() === 'Z') return true;
  return Number(timezone.slice(1, 3)) <= 23 && Number(timezone.slice(4, 6)) <= 59;
};

const isCop = (value: unknown): boolean => {
  if (value === null) return true;
  return (
    isRecord(value) &&
    hasExactKeys(value, copKeys) &&
    isFiniteNumber(value.x) &&
    value.x >= 0 &&
    value.x <= 1 &&
    isFiniteNumber(value.y) &&
    value.y >= 0 &&
    value.y <= 1
  );
};

const isFootData = (value: unknown): value is FootRealtimeData | null => {
  if (value === null) return true;
  if (!isRecord(value)) return false;
  const values = value.sensorValues;
  return (
    hasExactKeys(value, footKeys) &&
    typeof value.connected === 'boolean' &&
    isIntegerAtLeastZero(value.lastSequence) &&
    isIntegerAtLeastZero(value.deviceTimeMs) &&
    Array.isArray(values) &&
    (values.length === 6 || values.length === 8) &&
    values.every((item) => isFiniteNumber(item) && item >= 0 && item <= 100) &&
    isFiniteNumber(value.totalPressure) &&
    value.totalPressure >= 0 &&
    isCop(value.cop) &&
    typeof value.contactState === 'string' &&
    contactStates.includes(value.contactState as ContactState) &&
    isRfc3339DateTime(value.lastReceivedAt)
  );
};

const isQuality = (value: unknown): boolean =>
  isRecord(value) &&
  hasExactKeys(value, qualityKeys) &&
  isIntegerAtLeastZero(value.score) &&
  value.score <= 100 &&
  typeof value.level === 'string' &&
  qualityLevels.includes(value.level as QualityLevel) &&
  Array.isArray(value.flags) &&
  value.flags.every((flag) => typeof flag === 'string') &&
  new Set(value.flags).size === value.flags.length;

export const parseRealtimeValue = (value: unknown): RealtimePressureMessage => {
  if (!isRecord(value)) throw new Error('실시간 메시지 형식이 올바르지 않습니다.');

  if (
    !hasExactKeys(value, rootKeys) ||
    value.schemaVersion !== '1.0' ||
    !isUuid(value.sessionId) ||
    !isRfc3339DateTime(value.serverTime) ||
    !isIntegerAtLeastZero(value.elapsedTimeMs) ||
    value.status !== 'MEASURING' ||
    !isFootData(value.left) ||
    !isFootData(value.right) ||
    !isQuality(value.quality)
  ) {
    throw new Error('지원하지 않거나 손상된 실시간 메시지를 받았습니다.');
  }
  return value as RealtimePressureMessage;
};

export const parseRealtimeMessage = (payload: string): RealtimePressureMessage => {
  let value: unknown;
  try {
    value = JSON.parse(payload) as unknown;
  } catch {
    throw new Error('실시간 메시지가 올바른 JSON 형식이 아닙니다.');
  }
  return parseRealtimeValue(value);
};
