import {
  batteryLabel,
  observationPatternCodes,
  patternCodeLabel,
  qualityFlagLabel,
  receiverStatusHint,
  sessionSourceBadge,
} from './labels';

describe('세션 배지·수신기 안내', () => {
  it('sourceType과 sampleRateHz를 한 배지로 표시한다', () => {
    expect(sessionSourceBadge({ sourceType: 'DEVICE', sampleRateHz: 50 })).toBe('실기기 · 50Hz');
    expect(sessionSourceBadge({ sourceType: 'SIMULATED', sampleRateHz: 100 })).toBe('시뮬레이션 · 100Hz');
  });

  it.each([
    ['보고 전', {}, '수신기 업로드 상태가 아직 보고되지 않았습니다.'],
    ['null 보고', { receiverState: null, receiverPendingBatches: null }, '수신기 업로드 상태가 아직 보고되지 않았습니다.'],
    ['스트리밍', { receiverState: 'STREAMING' as const, receiverPendingBatches: 0 }, '수신기 스트리밍 중 · 미전송 배치 0개'],
    ['업로드 중', { receiverState: 'UPLOADING' as const, receiverPendingBatches: 3 }, '수신기 남은 배치 업로드 중 · 미전송 배치 3개'],
    ['완료', { receiverState: 'UPLOAD_COMPLETE' as const }, '수신기 업로드 완료'],
  ])('%s 상태의 수신기 안내 문구', (_case, session, expected) => {
    expect(receiverStatusHint(session)).toBe(expected);
  });
});

describe('patternCodeLabel', () => {
  it('rule-v1.2.0 6종 코드는 모두 전용 라벨을 가진다', () => {
    expect(observationPatternCodes).toHaveLength(6);
    for (const code of observationPatternCodes) {
      expect(patternCodeLabel(code)).not.toBe(code.replaceAll('_', ' '));
      expect(patternCodeLabel(code)).not.toMatch(/이전 분석/);
    }
    expect(patternCodeLabel('HIGH_MIDFOOT_LOAD')).toMatch(/이전 분석/);
  });
});

describe('batteryLabel', () => {
  it.each([
    ['heartbeat 전', {}, '미보정'],
    ['미보정 255→null', { lastBatteryPercent: null, lastBatteryMv: 3900 }, '미보정 · 3900 mV'],
    ['퍼센트와 mV', { lastBatteryPercent: 80, lastBatteryMv: 3900 }, '80% · 3900 mV'],
    ['퍼센트만', { lastBatteryPercent: 47.6, lastBatteryMv: null }, '48%'],
  ])('%s 배터리를 표시한다', (_case, device, expected) => {
    expect(batteryLabel(device)).toBe(expected);
  });
});

describe('qualityFlagLabel', () => {
  it.each([
    ['SENSOR_STUCK_OR_SATURATED', '일부 센서 값이 고정되었거나 측정 범위 끝에 머물렀습니다.'],
    ['OUT_OF_ORDER', '일부 센서 데이터의 순서가 뒤바뀌었습니다.'],
    ['DEVICE_TIME_JUMP', '센서 장치 시간이 불연속적으로 변했습니다.'],
    ['LEFT_DATA_MISSING', '왼발 센서 데이터가 없습니다.'],
    ['RIGHT_DATA_MISSING', '오른발 센서 데이터가 없습니다.'],
    ['LEFT_DATA_INCOMPLETE', '왼발 센서 데이터가 충분하지 않습니다.'],
    ['RIGHT_DATA_INCOMPLETE', '오른발 센서 데이터가 충분하지 않습니다.'],
    ['INSUFFICIENT_DATA', '분석에 사용할 센서 데이터가 충분하지 않습니다.'],
  ])('%s canonical flag를 사용자 문장으로 표시한다', (flag, expected) => {
    expect(qualityFlagLabel(flag)).toBe(expected);
  });

  it.each([
    'SEQUENCE_WRAP_SUSPECTED',
    'SAMPLE_RATE_MISMATCH',
    'RECEIVER_UPLOAD_INCOMPLETE',
    'FSR_ERROR_REPORTED',
    'IMU_ERROR_REPORTED',
    'BATTERY_LOW_REPORTED',
    'FILTERED_DATA_MODE',
    'LOW_DATA_QUALITY',
  ])('계약 1.1 플래그 %s는 원문 코드 대신 한국어 안내를 표시한다', (flag) => {
    const label = qualityFlagLabel(flag);
    expect(label).not.toBe(flag.replaceAll('_', ' ').toLocaleLowerCase('ko-KR'));
    expect(label).toMatch(/[가-힣]/);
  });
});
