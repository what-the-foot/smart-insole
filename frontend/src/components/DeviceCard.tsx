import type { DeviceResponse } from '../api/types';
import { formatDateTime } from '../utils/format';
import { batteryLabel, deviceStatusLabels } from '../utils/labels';
import { Icon } from './Icon';
import { StatusBadge, type StatusBadgeTone } from './StatusUi';

const statusTone = (status: DeviceResponse['status']): StatusBadgeTone => {
  if (status === 'ACTIVE') return 'positive';
  if (status === 'CALIBRATION_REQUIRED') return 'warning';
  if (status === 'DISCONNECTED') return 'danger';
  return 'neutral';
};

const calibrationLabel = (version: string | null | undefined): string => {
  if (!version) return '없음';
  return version === 'identity-v1' ? 'identity-v1 (기능 검증용)' : version;
};

const CURRENT_ADC_MAX = 4095;

// heartbeat 배터리 퍼센트(0..100). 미보정(255)은 null로 오므로 막대를 비운다.
const batteryFillPercent = (device: Pick<DeviceResponse, 'lastBatteryPercent'>): number | null => {
  const percent = device.lastBatteryPercent ?? null;
  if (percent === null || !Number.isFinite(percent)) return null;
  return Math.min(100, Math.max(0, percent));
};

export function DeviceCard({
  device,
  compact = false,
}: {
  device: DeviceResponse;
  compact?: boolean;
}) {
  const side = device.footSide === 'LEFT' ? 'left' : 'right';
  const fill = batteryFillPercent(device);
  return (
    <article
      className={`device-card device-card--${side}${compact ? ' device-card--compact' : ''}`}
    >
      <div className="device-card__top">
        <span aria-hidden="true" className={`device-side device-side--${side}`}>
          {device.footSide === 'LEFT' ? 'L' : 'R'}
        </span>
        <div>
          <p className="eyebrow">{device.footSide === 'LEFT' ? '왼발 인솔' : '오른발 인솔'}</p>
          <h3>{device.displayName}</h3>
        </div>
        <StatusBadge tone={statusTone(device.status)}>
          {deviceStatusLabels[device.status]}
        </StatusBadge>
      </div>
      <div className={`device-battery${fill === null ? ' device-battery--unknown' : ''}`}>
        <span aria-hidden="true" className="device-battery__icon">
          <Icon name="battery" />
        </span>
        <span className="device-battery__label">마지막 배터리</span>
        <span aria-hidden="true" className="device-battery__track">
          <span className="device-battery__fill" style={{ width: `${fill ?? 0}%` }} />
        </span>
        <span className="device-battery__value">{batteryLabel(device)}</span>
      </div>
      <dl className="device-meta">
        <div>
          <dt>시리얼</dt>
          <dd>{device.serialNumber}</dd>
        </div>
        <div>
          <dt>센서</dt>
          <dd>{device.sensorCount}개</dd>
        </div>
        <div>
          <dt>배치</dt>
          <dd>{device.sensorLayoutVersion}</dd>
        </div>
        <div>
          <dt>활성 보정</dt>
          <dd>{calibrationLabel(device.activeCalibrationVersion)}</dd>
        </div>
        {!compact ? (
          <>
            <div>
              <dt>펌웨어</dt>
              <dd>{device.firmwareVersion}</dd>
            </div>
            <div>
              <dt>마지막 연결</dt>
              <dd>{formatDateTime(device.lastSeenAt)}</dd>
            </div>
            <div>
              <dt>ADC 최댓값</dt>
              <dd>{device.adcMax}</dd>
            </div>
            <div>
              <dt>등록일</dt>
              <dd>{formatDateTime(device.registeredAt)}</dd>
            </div>
          </>
        ) : null}
      </dl>
      {!compact && device.adcMax !== CURRENT_ADC_MAX ? (
        <p className="card-notice">
          <Icon name="alert" />
          레거시 ADC 스케일({device.adcMax}) 기기입니다. 실기기 측정에는 4095 스케일로 등록된 인솔을
          사용해 주세요.
        </p>
      ) : null}
      {!device.activeCalibrationVersion ? (
        <p className="card-notice card-notice--danger">
          <Icon name="alert" />
          활성 보정이 없어 측정에 사용할 수 없습니다.
        </p>
      ) : null}
      {device.activeCalibrationVersion === 'identity-v1' ? (
        <p className="card-notice">
          <Icon name="alert" />
          기능 검증용 기본 보정이며 개인 맞춤 또는 임상 보정을 의미하지 않습니다.
        </p>
      ) : null}
      {device.status === 'CALIBRATION_REQUIRED' ? (
        <p className="card-notice">
          <Icon name="alert" />
          측정 전에 인솔 보정 상태를 확인해 주세요.
        </p>
      ) : null}
      {device.status === 'DISCONNECTED' ? (
        <p className="card-notice card-notice--danger">
          <Icon name="alert" />
          Receiver와 인솔 연결을 확인해 주세요.
        </p>
      ) : null}
    </article>
  );
}
