import type {
  ContactState,
  DeviceResponse,
  DeviceStatus,
  MeasurementStatus,
  ObservationLevel,
  ObservationPatternCode,
  QualityLevel,
  ReceiverUploadState,
  SourceType,
} from '../api/types';

// rule-v1.2.0 관찰 단계(DEC-030). 발생 비율 0.20/0.60·최소 창 4는 백엔드 제안값이며 질환 판단이 아니다.
export const observationLevelLabels: Record<ObservationLevel, string> = {
  NOT_OBSERVED: '관찰되지 않음',
  PARTIALLY_OBSERVED: '일부 관찰',
  REPEATEDLY_OBSERVED: '반복 관찰',
};

export const observationLevelOrder: readonly ObservationLevel[] = [
  'REPEATEDLY_OBSERVED',
  'PARTIALLY_OBSERVED',
  'NOT_OBSERVED',
];

export const OBSERVATION_LEVEL_UNAVAILABLE = '관찰 단계 미제공(이전 분석)';

// 예) '발생 비율 62% (13/21 걸음)'. LEFT_RIGHT_ASYMMETRY의 창은 좌우 걸음 쌍이다.
export const occurrenceRateText = (item: {
  code: string;
  occurrenceRate?: number | null;
  observedCount?: number | null;
  windowCount?: number | null;
}): string | null => {
  const rate = item.occurrenceRate ?? null;
  const observed = item.observedCount ?? null;
  const windows = item.windowCount ?? null;
  if (rate === null || observed === null || windows === null) return null;
  const unit = item.code === 'LEFT_RIGHT_ASYMMETRY' ? '걸음 쌍' : '걸음';
  return `발생 비율 ${Math.round(rate * 100)}% (${observed}/${windows} ${unit})`;
};

export const measurementStatusLabels: Record<MeasurementStatus, string> = {
  CREATED: '준비됨',
  MEASURING: '측정 중',
  PROCESSING: '분석 중',
  COMPLETED: '완료',
  CANCELLED: '취소됨',
  FAILED: '실패',
};

// sourceType은 자동 판별하지 않는다. 기본 DEVICE, 시뮬레이터만 SIMULATED(DEC-028).
export const sourceTypeLabels: Record<SourceType, string> = {
  DEVICE: '실기기',
  SIMULATED: '시뮬레이션',
};

// 헤더 배지 문구: 예) '실기기 · 50Hz'
export const sessionSourceBadge = (session: {
  sourceType: SourceType;
  sampleRateHz: number;
}): string => `${sourceTypeLabels[session.sourceType]} · ${session.sampleRateHz}Hz`;

export const receiverStateLabels: Record<ReceiverUploadState, string> = {
  STREAMING: '스트리밍 중',
  UPLOADING: '남은 배치 업로드 중',
  UPLOAD_COMPLETE: '업로드 완료',
};

// 완료 버튼 옆 안내. receiverState가 null이면 수신기가 아직 보고하지 않은 것이다.
export const receiverStatusHint = (session: {
  receiverState?: ReceiverUploadState | null;
  receiverPendingBatches?: number | null;
}): string => {
  const state = session.receiverState ?? null;
  if (state === null) return '수신기 업로드 상태가 아직 보고되지 않았습니다.';
  const pending = session.receiverPendingBatches ?? null;
  const pendingText = pending === null ? '' : ` · 미전송 배치 ${pending}개`;
  return `수신기 ${receiverStateLabels[state]}${pendingText}`;
};

export const deviceStatusLabels: Record<DeviceStatus, string> = {
  ACTIVE: '사용 가능',
  INACTIVE: '비활성',
  DISCONNECTED: '연결 끊김',
  CALIBRATION_REQUIRED: '보정 필요',
};

// Status battery_pct 255(미보정/미측정)는 수신기가 null로 보낸다. heartbeat 전이면 필드 자체가 없다.
export const batteryLabel = (
  device: Pick<DeviceResponse, 'lastBatteryPercent' | 'lastBatteryMv'>,
): string => {
  const percent = device.lastBatteryPercent ?? null;
  const millivolts = device.lastBatteryMv ?? null;
  const percentText = percent === null ? '미보정' : `${Math.round(percent)}%`;
  return millivolts === null ? percentText : `${percentText} · ${millivolts} mV`;
};

export const qualityLabels: Record<QualityLevel, string> = {
  GOOD: '좋음',
  ACCEPTABLE: '확인 필요',
  POOR: '낮음',
};

export const contactStateLabels: Record<ContactState, string> = {
  CONTACT: '접촉',
  NO_CONTACT: '비접촉',
  UNKNOWN: '확인 중',
};

// 결과·실시간 화면 용어. 센서 값은 보정된 압력이 아니라 세션 adcMax 기준 상대 신호이므로
// '최대 압력'·'CoP' 대신 아래 용어만 사용한다(회귀 테스트: ResultContent.test.tsx).
export const resultTerms = {
  signalShare: '센서 신호 비율',
  peakSignal: '최대 센서 신호',
  estimatedCop: '추정 압력중심',
  totalSignal: '총 신호',
} as const;

export const qualityFlagLabel = (flag: string): string => {
  const labels: Record<string, string> = {
    RIGHT_DEVICE_DISCONNECTED: '오른발 인솔의 데이터가 수신되지 않습니다.',
    LEFT_DEVICE_DISCONNECTED: '왼발 인솔의 데이터가 수신되지 않습니다.',
    SEQUENCE_GAP: '일부 센서 데이터가 누락되었습니다.',
    SENSOR_STUCK: '일부 센서 값의 변화가 감지되지 않습니다.',
    SENSOR_SATURATION: '일부 센서가 측정 범위를 벗어났습니다.',
    SENSOR_STUCK_OR_SATURATED: '일부 센서 값이 고정되었거나 측정 범위 끝에 머물렀습니다.',
    OUT_OF_ORDER: '일부 센서 데이터의 순서가 뒤바뀌었습니다.',
    DEVICE_TIME_JUMP: '센서 장치 시간이 불연속적으로 변했습니다.',
    LEFT_DATA_MISSING: '왼발 센서 데이터가 없습니다.',
    RIGHT_DATA_MISSING: '오른발 센서 데이터가 없습니다.',
    LEFT_DATA_INCOMPLETE: '왼발 센서 데이터가 충분하지 않습니다.',
    RIGHT_DATA_INCOMPLETE: '오른발 센서 데이터가 충분하지 않습니다.',
    INSUFFICIENT_DATA: '분석에 사용할 센서 데이터가 충분하지 않습니다.',
    LOW_DATA_QUALITY: '데이터 품질이 낮아 분석 결과의 신뢰도가 제한됩니다.',
    // 계약 1.1 / rule-v1.2.0에서 추가된 플래그(DEC-027, DEC-028, DEC-024)
    SEQUENCE_WRAP_SUSPECTED: '센서 프레임 번호가 되감긴 것으로 의심됩니다. 프레임 순서 통계가 부정확할 수 있습니다.',
    SAMPLE_RATE_MISMATCH: '실제 수신 간격이 세션 전송률 설정과 맞지 않습니다.',
    RECEIVER_UPLOAD_INCOMPLETE: '수신기가 업로드를 끝내지 못했습니다. 일부 프레임이 누락되었을 수 있습니다.',
    FSR_ERROR_REPORTED: '인솔이 압력 센서 오류를 보고했습니다.',
    IMU_ERROR_REPORTED: '인솔이 관성 센서(IMU) 오류를 보고했습니다.',
    BATTERY_LOW_REPORTED: '인솔 배터리가 낮다고 보고되었습니다.',
    FILTERED_DATA_MODE: '필터링된 데이터 모드로 수신되어 원시 신호와 다를 수 있습니다.',
  };
  return labels[flag] ?? flag.replaceAll('_', ' ').toLocaleLowerCase('ko-KR');
};

// rule-v1.2.0 패턴 코드 6종(DEC-030). 기록 필터 옵션도 이 배열만 사용한다.
export const observationPatternCodes: readonly ObservationPatternCode[] = [
  'MEDIAL_LOAD_TENDENCY',
  'LATERAL_LOAD_TENDENCY',
  'LEFT_RIGHT_ASYMMETRY',
  'LOW_HALLUX_SIGNAL',
  'FOREFOOT_LOAD_TENDENCY',
  'REARFOOT_LOAD_TENDENCY',
];

export const patternCodeLabel = (code: string): string => {
  const labels: Record<string, string> = {
    MEDIAL_LOAD_TENDENCY: '내측 하중 경향',
    LATERAL_LOAD_TENDENCY: '외측 하중 경향',
    LEFT_RIGHT_ASYMMETRY: '좌우 비대칭 경향',
    LOW_HALLUX_SIGNAL: '엄지 신호 낮음',
    FOREFOOT_LOAD_TENDENCY: '전족부 하중 경향',
    REARFOOT_LOAD_TENDENCY: '후족부 하중 경향',
    // 이전 알고리즘(rule-v1.1.0 이하) 기록에만 남아 있는 코드. 새 결과에는 나오지 않는다.
    LOW_DATA_QUALITY: '데이터 품질 확인 필요 (이전 분석)',
    HIGH_MIDFOOT_LOAD: '중족부 하중 증가 경향 (이전 분석)',
    SHORT_CONTACT_TIME: '짧은 접촉 시간 경향 (이전 분석)',
  };
  return labels[code] ?? code.replaceAll('_', ' ');
};
