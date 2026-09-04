import type {
  ContactState,
  DeviceStatus,
  MeasurementStatus,
  QualityLevel,
} from '../api/types';

export const measurementStatusLabels: Record<MeasurementStatus, string> = {
  CREATED: '준비됨',
  MEASURING: '측정 중',
  PROCESSING: '분석 중',
  COMPLETED: '완료',
  CANCELLED: '취소됨',
  FAILED: '실패',
};

export const deviceStatusLabels: Record<DeviceStatus, string> = {
  ACTIVE: '사용 가능',
  INACTIVE: '비활성',
  DISCONNECTED: '연결 끊김',
  CALIBRATION_REQUIRED: '보정 필요',
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
  };
  return labels[flag] ?? flag.replaceAll('_', ' ').toLocaleLowerCase('ko-KR');
};

export const patternCodeLabel = (code: string): string => {
  const labels: Record<string, string> = {
    LOW_DATA_QUALITY: '데이터 품질 확인 필요',
    LEFT_RIGHT_ASYMMETRY: '좌우 접촉 시간 차이',
    MEDIAL_LOAD_TENDENCY: '내측 압력 집중 경향',
    LATERAL_LOAD_TENDENCY: '외측 압력 집중 경향',
    HIGH_MIDFOOT_LOAD: '중족부 압력 증가 경향',
    SHORT_CONTACT_TIME: '짧은 접촉 시간 경향',
  };
  return labels[code] ?? code.replaceAll('_', ' ');
};
