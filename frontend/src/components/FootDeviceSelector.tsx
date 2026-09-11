import type { DeviceResponse, FootSide } from '../api/types';
import { deviceStatusLabels } from '../utils/labels';
import { Icon } from './Icon';

const calibrationLabel = (version: string | null | undefined): string => {
  if (!version) return '보정 없음';
  return version === 'identity-v1' ? '기능 검증용 기본 보정' : `보정 ${version}`;
};

export function FootDeviceSelector({
  side,
  devices,
  selectedId,
  onChange,
}: {
  side: FootSide;
  devices: DeviceResponse[];
  selectedId: string;
  onChange: (deviceId: string) => void;
}) {
  const label = side === 'LEFT' ? '왼발' : '오른발';
  const matching = devices.filter((device) => device.footSide === side);
  const selected = matching.find((device) => device.deviceId === selectedId);

  return (
    <fieldset className={`foot-selector foot-selector--${side.toLowerCase()}`}>
      <legend>
        <span className={`device-side device-side--${side.toLowerCase()}`}>
          {side === 'LEFT' ? 'L' : 'R'}
        </span>
        <span>
          <strong>{label} 인솔</strong>
          <small>{side} 기기만 선택할 수 있어요.</small>
        </span>
      </legend>
      {matching.length === 0 ? (
        <p className="selector-empty">
          <Icon name="alert" />
          등록된 {label} 인솔이 없습니다.
        </p>
      ) : (
        <div className="selector-options">
          {matching.map((device) => {
            const isSelected = selectedId === device.deviceId;
            return (
              <label
                className={`selector-option${isSelected ? ' selector-option--selected' : ''}`}
                key={device.deviceId}
              >
                <input
                  checked={isSelected}
                  disabled={device.status === 'INACTIVE' || !device.activeCalibrationVersion}
                  name={`${side.toLowerCase()}Device`}
                  onChange={() => onChange(device.deviceId)}
                  type="radio"
                  value={device.deviceId}
                />
                <span>
                  <strong>{device.displayName}</strong>
                  <small>
                    {device.sensorCount}센서 · {deviceStatusLabels[device.status]} ·{' '}
                    {calibrationLabel(device.activeCalibrationVersion)}
                  </small>
                  <span className="selector-option__meta">
                    <span>{device.serialNumber}</span>
                    <span>배치 {device.sensorLayoutVersion}</span>
                  </span>
                </span>
                <Icon name={isSelected ? 'check' : 'arrow'} />
              </label>
            );
          })}
        </div>
      )}
      {selected ? (
        <p className="foot-selector__selected">
          <Icon name="check" />
          {label} 인솔 선택됨 · {selected.displayName}
        </p>
      ) : null}
      {matching.some((device) => !device.activeCalibrationVersion) ? (
        <p className="card-notice card-notice--danger">
          <Icon name="alert" />
          활성 보정이 없는 인솔은 선택할 수 없습니다.
        </p>
      ) : null}
      {selected?.activeCalibrationVersion === 'identity-v1' ? (
        <p className="card-notice">
          <Icon name="alert" />
          기능 검증용 기본 보정은 개인 맞춤 보정을 의미하지 않습니다.
        </p>
      ) : null}
      {selected?.status === 'CALIBRATION_REQUIRED' ? (
        <p className="card-notice">
          <Icon name="alert" />이 인솔은 보정 확인이 필요합니다. 상태를 확인한 뒤 진행해 주세요.
        </p>
      ) : null}
      {selected?.status === 'DISCONNECTED' ? (
        <p className="card-notice card-notice--danger">
          <Icon name="alert" />
          현재 연결이 확인되지 않습니다. Receiver와 인솔을 확인해 주세요.
        </p>
      ) : null}
    </fieldset>
  );
}
