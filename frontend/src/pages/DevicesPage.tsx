import { useState, type SyntheticEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { deviceApi } from '../api/services';
import { queryKeys, useDevices } from '../api/queries';
import type { FootSide, RegisterDeviceRequest } from '../api/types';
import { DeviceCard } from '../components/DeviceCard';
import { Icon } from '../components/Icon';
import { ErrorPanel, PageHeader, Spinner, StatePanel } from '../components/StatusUi';
import {
  DEFAULT_EIGHT_SENSOR_LAYOUT_VERSION,
  defaultLayoutVersionForSensorCount,
  isSensorCountRegistrable,
  SIX_SENSOR_REGISTRATION_NOTE,
  type SupportedSensorCount,
} from '../features/devices/layouts';

const initialForm: RegisterDeviceRequest = {
  serialNumber: '',
  displayName: '',
  footSide: 'LEFT',
  sensorCount: 8,
  sensorLayoutVersion: DEFAULT_EIGHT_SENSOR_LAYOUT_VERSION,
  firmwareVersion: '0.2.0',
  adcMax: 4095,
};

export function DevicesPage() {
  const devices = useDevices();
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<RegisterDeviceRequest>(initialForm);
  const [success, setSuccess] = useState<string | null>(null);

  const register = useMutation({
    mutationFn: deviceApi.register,
    onSuccess: async (device) => {
      setSuccess(`${device.displayName} 인솔을 등록했습니다.`);
      setForm(initialForm);
      setShowForm(false);
      await queryClient.invalidateQueries({ queryKey: queryKeys.devices });
    },
  });

  const updateField = <K extends keyof RegisterDeviceRequest>(
    key: K,
    value: RegisterDeviceRequest[K],
  ) => setForm((current) => ({ ...current, [key]: value }));

  const updateSensorCount = (sensorCount: SupportedSensorCount) => {
    const layoutVersion = defaultLayoutVersionForSensorCount(sensorCount);
    if (layoutVersion === null) return;
    setForm((current) => ({ ...current, sensorCount, sensorLayoutVersion: layoutVersion }));
  };

  const handleSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSuccess(null);
    register.mutate(form);
  };

  return (
    <div className="page-stack">
      <PageHeader
        eyebrow="DEVICE MANAGEMENT"
        title="내 스마트 인솔"
        description="기기의 방향과 센서 배치를 확인하고 측정을 준비하세요."
        action={
          <button className="button" onClick={() => setShowForm((value) => !value)}>
            <Icon name={showForm ? 'x' : 'plus'} />
            {showForm ? '닫기' : '인솔 등록'}
          </button>
        }
      />
      {success ? (
        <p className="notice notice--success" role="status">
          <Icon name="check" />
          {success}
        </p>
      ) : null}

      {showForm ? (
        <section className="content-card registration-card" aria-labelledby="register-device-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">NEW DEVICE</p>
              <h2 id="register-device-title">인솔 등록</h2>
            </div>
          </div>
          {register.isError ? (
            <p className="form-error" role="alert">
              <Icon name="alert" />
              {register.error.message}
            </p>
          ) : null}
          <form className="device-form" onSubmit={handleSubmit}>
            <label className="field">
              <span>표시 이름</span>
              <input
                maxLength={100}
                onChange={(event) => updateField('displayName', event.target.value)}
                placeholder="예: 내 왼발 인솔"
                required
                value={form.displayName}
              />
            </label>
            <label className="field">
              <span>시리얼 번호</span>
              <input
                maxLength={100}
                onChange={(event) => updateField('serialNumber', event.target.value)}
                placeholder="INSOLE-L-001"
                required
                value={form.serialNumber}
              />
            </label>
            <label className="field">
              <span>착용 방향</span>
              <select
                onChange={(event) => updateField('footSide', event.target.value as FootSide)}
                value={form.footSide}
              >
                <option value="LEFT">왼발 (LEFT)</option>
                <option value="RIGHT">오른발 (RIGHT)</option>
              </select>
            </label>
            <label className="field">
              <span>센서 수</span>
              <select
                aria-describedby="sensor-count-help"
                onChange={(event) =>
                  updateSensorCount(Number(event.target.value) as SupportedSensorCount)
                }
                value={form.sensorCount}
              >
                <option value={8}>8개 (S01..S08)</option>
                <option disabled={!isSensorCountRegistrable(6)} value={6}>
                  6개 (등록 불가)
                </option>
              </select>
              <small id="sensor-count-help">{SIX_SENSOR_REGISTRATION_NOTE}</small>
            </label>
            <label className="field">
              <span>센서 배치 버전</span>
              <input
                aria-describedby="layout-version-help"
                readOnly
                value={form.sensorLayoutVersion}
              />
              <small id="layout-version-help">
                센서 수에 맞는 활성 기본 배치를 자동으로 선택합니다. 레거시 배치(layout-v1,
                layout-v1-6)는 조회만 가능하고 새로 등록할 수 없습니다.
              </small>
            </label>
            <label className="field">
              <span>ADC 최댓값</span>
              <input aria-describedby="adc-max-help" readOnly value={form.adcMax} />
              <small id="adc-max-help">
                RAW ADC 12비트 스케일입니다. 현재 4095만 등록할 수 있습니다.
              </small>
            </label>
            <label className="field">
              <span>펌웨어 버전</span>
              <input
                maxLength={50}
                onChange={(event) => updateField('firmwareVersion', event.target.value)}
                required
                value={form.firmwareVersion}
              />
            </label>
            <div className="form-actions">
              <button
                className="button button--secondary"
                onClick={() => setShowForm(false)}
                type="button"
              >
                취소
              </button>
              <button className="button" disabled={register.isPending} type="submit">
                {register.isPending ? <Spinner label="등록 중" /> : '등록 완료'}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {devices.isPending ? <Spinner label="인솔 목록 불러오는 중" /> : null}
      {devices.isError ? (
        <ErrorPanel error={devices.error} retry={() => void devices.refetch()} />
      ) : null}
      {devices.data?.length === 0 ? (
        <StatePanel
          icon="device"
          title="등록된 인솔이 없어요"
          description="왼발과 오른발 인솔을 각각 등록해 주세요."
          action={
            <button className="button" onClick={() => setShowForm(true)}>
              <Icon name="plus" />첫 인솔 등록
            </button>
          }
        />
      ) : null}
      {devices.data?.length ? (
        <section aria-label="등록된 인솔" className="device-grid">
          {devices.data.map((device) => (
            <DeviceCard device={device} key={device.deviceId} />
          ))}
        </section>
      ) : null}
    </div>
  );
}
