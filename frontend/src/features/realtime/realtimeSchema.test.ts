import { parseRealtimeMessage, parseRealtimeValue } from './realtimeSchema';

const valid = {
  schemaVersion: '1.0',
  sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
  serverTime: '2026-09-02T07:11:10.500Z',
  elapsedTimeMs: 10500,
  status: 'MEASURING',
  left: {
    connected: true,
    lastSequence: 111,
    deviceTimeMs: 1110,
    sensorValues: [12, 24, 51, 80, 61, 30],
    totalPressure: 258,
    cop: { x: 0.42, y: 0.73 },
    contactState: 'CONTACT',
    lastReceivedAt: '2026-09-02T07:11:10.490Z',
  },
  right: null,
  quality: { score: 78, level: 'ACCEPTABLE', flags: ['RIGHT_DEVICE_DISCONNECTED'] },
};

describe('실시간 메시지 검증', () => {
  it('한쪽 발 null인 계약 메시지를 허용한다', () => {
    expect(parseRealtimeMessage(JSON.stringify(valid)).right).toBeNull();
  });

  it('이미 디코딩된 REST snapshot도 같은 계약으로 검증한다', () => {
    expect(parseRealtimeValue(valid)).toMatchObject({
      sessionId: valid.sessionId,
      schemaVersion: '1.0',
    });
  });

  it('센서 범위를 벗어난 메시지를 거부한다', () => {
    const malformed = { ...valid, left: { ...valid.left, sensorValues: [0, 20, 40, 60, 80, 101] } };
    expect(() => parseRealtimeMessage(JSON.stringify(malformed))).toThrow('손상된 실시간 메시지');
  });

  it('7개 센서 메시지를 거부한다', () => {
    const malformed = {
      ...valid,
      left: { ...valid.left, sensorValues: [0, 10, 20, 30, 40, 50, 60] },
    };
    expect(() => parseRealtimeValue(malformed)).toThrow('손상된 실시간 메시지');
  });

  it.each([
    ['root', { ...valid, unexpected: true }],
    ['foot', { ...valid, left: { ...valid.left, unexpected: true } }],
    ['cop', { ...valid, left: { ...valid.left, cop: { ...valid.left.cop, unexpected: true } } }],
    ['quality', { ...valid, quality: { ...valid.quality, unexpected: true } }],
  ])('%s 객체의 추가 필드를 거부한다', (_scope, malformed) => {
    expect(() => parseRealtimeValue(malformed)).toThrow('손상된 실시간 메시지');
  });

  it.each([
    ['UUID', { ...valid, sessionId: 'not-a-uuid' }],
    ['date-only', { ...valid, serverTime: '2026-09-02' }],
    ['impossible date', { ...valid, serverTime: '2026-02-30T07:11:10Z' }],
    [
      'foot date-time',
      { ...valid, left: { ...valid.left, lastReceivedAt: 'September 2, 2026 07:11:10' } },
    ],
  ])('잘못된 %s 형식을 거부한다', (_field, malformed) => {
    expect(() => parseRealtimeValue(malformed)).toThrow('손상된 실시간 메시지');
  });

  it('RFC 3339 offset date-time을 허용한다', () => {
    const withOffset = { ...valid, serverTime: '2026-09-02T16:11:10.500+09:00' };
    expect(parseRealtimeValue(withOffset).serverTime).toBe(withOffset.serverTime);
  });

  it('계약 1.1의 새 품질 flag 이름을 자유 문자열로 허용한다', () => {
    const flags = [
      'SEQUENCE_WRAP_SUSPECTED',
      'SAMPLE_RATE_MISMATCH',
      'RECEIVER_UPLOAD_INCOMPLETE',
      'FSR_ERROR_REPORTED',
      'IMU_ERROR_REPORTED',
      'BATTERY_LOW_REPORTED',
      'FILTERED_DATA_MODE',
    ];
    expect(
      parseRealtimeValue({ ...valid, quality: { ...valid.quality, flags } }).quality.flags,
    ).toEqual(flags);
  });

  it('중복된 품질 flag를 거부한다', () => {
    const malformed = {
      ...valid,
      quality: {
        ...valid.quality,
        flags: ['RIGHT_DEVICE_DISCONNECTED', 'RIGHT_DEVICE_DISCONNECTED'],
      },
    };
    expect(() => parseRealtimeValue(malformed)).toThrow('손상된 실시간 메시지');
  });
});
