import { useState, type SyntheticEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { SIMULATION_SESSION_OPTION_ENABLED } from '../api/config';
import { useDevices } from '../api/queries';
import { measurementApi } from '../api/services';
import type { SampleRateHz } from '../api/types';
import { FootDeviceSelector } from '../components/FootDeviceSelector';
import { Icon } from '../components/Icon';
import { ErrorPanel, PageHeader, Spinner, StatePanel } from '../components/StatusUi';
import { readPreferences } from '../app/preferences';
import { SAMPLE_RATE_OPTIONS, sampleRateLabels } from '../features/measurement/sampleRates';

// 준비 단계: 1 인솔 선택 → 2 전송률 → 3 시작(live 화면에서 '측정 시작').
const SETUP_STEPS = ['인솔 선택', '전송률', '시작'] as const;

export function NewMeasurementPage() {
  const devices = useDevices();
  const navigate = useNavigate();
  const [leftDeviceId, setLeftDeviceId] = useState('');
  const [rightDeviceId, setRightDeviceId] = useState('');
  // 설정 페이지의 '기본 전송률'(localStorage)을 우선하고, 없으면 env 기본값으로 돌아간다.
  const [sampleRateHz, setSampleRateHz] = useState<SampleRateHz>(
    () => readPreferences().defaultSampleRateHz,
  );
  const [simulated, setSimulated] = useState(false);
  const [memo, setMemo] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const createSession = useMutation({
    mutationFn: () =>
      measurementApi.create({
        leftDeviceId,
        rightDeviceId,
        sampleRateHz,
        // 계약 기본값은 DEVICE. 시뮬레이터(개발용 체크박스)만 SIMULATED를 명시한다(DEC-028).
        sourceType: SIMULATION_SESSION_OPTION_ENABLED && simulated ? 'SIMULATED' : 'DEVICE',
        memo: memo.trim() || null,
      }),
    onSuccess: (session) => void navigate(`/measurements/${session.sessionId}/live`),
  });

  const bothSelected = Boolean(leftDeviceId && rightDeviceId);
  // 0-based. 양발을 고르기 전엔 1단계, 골랐으면 2단계(전송률 확인 후 계속), 3단계는 live 화면의 '측정 시작'.
  const activeStep = bothSelected ? 1 : 0;

  const handleSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFormError(null);
    if (!leftDeviceId || !rightDeviceId) {
      setFormError('왼발과 오른발 인솔을 모두 선택해 주세요.');
      return;
    }
    if (leftDeviceId === rightDeviceId) {
      setFormError('같은 기기를 양쪽 발에 사용할 수 없습니다.');
      return;
    }
    const left = devices.data?.find((device) => device.deviceId === leftDeviceId);
    const right = devices.data?.find((device) => device.deviceId === rightDeviceId);
    if (left?.footSide !== 'LEFT' || right?.footSide !== 'RIGHT') {
      setFormError('인솔 방향이 올바르지 않습니다. LEFT와 RIGHT 표시를 확인해 주세요.');
      return;
    }
    if (!left.activeCalibrationVersion || !right.activeCalibrationVersion) {
      setFormError('활성 보정이 있는 LEFT와 RIGHT 인솔을 선택해 주세요.');
      return;
    }
    createSession.mutate();
  };

  return (
    <div className="page-stack measurement-setup-page">
      <PageHeader
        eyebrow="NEW MEASUREMENT"
        title="새 측정 준비"
        description="양쪽 인솔을 선택하고 연결 상태를 확인하세요."
      />
      <ol className="stepper stepper--setup" aria-label="측정 준비 단계">
        {SETUP_STEPS.map((label, index) => {
          const state = index < activeStep ? 'done' : index === activeStep ? 'active' : 'todo';
          return (
            <li
              aria-current={state === 'active' ? 'step' : undefined}
              className={
                state === 'done'
                  ? 'stepper__done'
                  : state === 'active'
                    ? 'stepper__active'
                    : undefined
              }
              key={label}
            >
              <span aria-hidden="true">{state === 'done' ? <Icon name="check" /> : index + 1}</span>
              <span className="sr-only">{`${index + 1}단계${state === 'done' ? ' 완료' : ''}: `}</span>
              {label}
            </li>
          );
        })}
      </ol>
      {devices.isPending ? <Spinner label="사용 가능한 인솔 확인 중" /> : null}
      {devices.isError ? (
        <ErrorPanel error={devices.error} retry={() => void devices.refetch()} />
      ) : null}
      {devices.data?.length === 0 ? (
        <StatePanel
          icon="device"
          title="먼저 인솔을 등록해 주세요"
          description="측정에는 LEFT와 RIGHT 인솔이 각각 필요합니다."
          action={
            <Link className="button" to="/devices">
              인솔 등록하기
            </Link>
          }
        />
      ) : null}
      {devices.data?.length ? (
        <form className="measurement-form" onSubmit={handleSubmit}>
          <div className="bilateral-selectors">
            <FootDeviceSelector
              devices={devices.data}
              onChange={setLeftDeviceId}
              selectedId={leftDeviceId}
              side="LEFT"
            />
            <FootDeviceSelector
              devices={devices.data}
              onChange={setRightDeviceId}
              selectedId={rightDeviceId}
              side="RIGHT"
            />
          </div>
          <section className="content-card setup-details" aria-labelledby="sample-rate-title">
            <div>
              <p className="eyebrow">SAMPLE RATE</p>
              <h2 id="sample-rate-title">전송률</h2>
              <p className="muted">
                세션에 저장되는 sampleRateHz는 수신기 전송률의 단일 기준입니다. 실기기 기본값은
                50Hz입니다.
              </p>
            </div>
            <div>
              <fieldset className="sample-rate-fieldset">
                <legend className="sr-only">전송률 선택</legend>
                <div className="selector-options">
                  {SAMPLE_RATE_OPTIONS.map((option) => (
                    <label
                      className={`selector-option${sampleRateHz === option ? ' selector-option--selected' : ''}`}
                      key={option}
                    >
                      <input
                        checked={sampleRateHz === option}
                        name="sampleRateHz"
                        onChange={() => setSampleRateHz(option)}
                        type="radio"
                        value={option}
                      />
                      <span>
                        <strong>{sampleRateLabels[option].title}</strong>
                        <small>{sampleRateLabels[option].description}</small>
                      </span>
                      <Icon name={sampleRateHz === option ? 'check' : 'arrow'} />
                    </label>
                  ))}
                </div>
              </fieldset>
              {SIMULATION_SESSION_OPTION_ENABLED ? (
                <label className="checkbox-field">
                  <input
                    checked={simulated}
                    name="simulated"
                    onChange={(event) => setSimulated(event.target.checked)}
                    type="checkbox"
                  />
                  <span>
                    <strong>시뮬레이션 세션</strong>
                    <small>
                      개발 모드 전용. sourceType을 SIMULATED로 보내 mock receiver·E2E 데이터를
                      실기기 기록과 구분합니다.
                    </small>
                  </span>
                </label>
              ) : null}
            </div>
          </section>
          <section className="content-card setup-details" aria-labelledby="measurement-note-title">
            <div>
              <p className="eyebrow">OPTIONAL NOTE</p>
              <h2 id="measurement-note-title">측정 메모</h2>
              <p className="muted">환경이나 목적을 간단히 적어 두면 기록을 찾기 쉬워요.</p>
            </div>
            <label className="field">
              <span className="sr-only">측정 메모</span>
              <textarea
                maxLength={500}
                onChange={(event) => setMemo(event.target.value)}
                placeholder="예: 실내 평지에서 편안한 속도로 보행"
                rows={3}
                value={memo}
              />
              <small>{memo.length}/500</small>
            </label>
          </section>
          <section className="readiness-card">
            <Icon name="shield" />
            <div>
              <h2>시작 전 확인해 주세요</h2>
              <ul>
                <li>주변에 걸려 넘어질 물건이 없는지 확인하세요.</li>
                <li>통증이나 어지럼이 느껴지면 바로 측정을 중단하세요.</li>
                <li>연결이 불안정하면 Receiver와 인솔 상태를 먼저 확인하세요.</li>
              </ul>
              <p className="readiness-card__protocol">
                <Icon name="clock" />
                <span>
                  <strong>측정을 시작하면 2초간 가만히 서 있어 주세요.</strong>
                  <small>
                    정강이 IMU 기준 자세는 측정 시작 직후 정지 구간에서 자동으로 잡힙니다. 그 뒤
                    평소처럼 걸으면 됩니다.
                  </small>
                </span>
              </p>
            </div>
          </section>
          {formError ? (
            <p className="form-error" role="alert">
              <Icon name="alert" />
              {formError}
            </p>
          ) : null}
          {createSession.isError ? (
            <p className="form-error" role="alert">
              <Icon name="alert" />
              {createSession.error.message} 기기 상태를 확인한 뒤 다시 시도해 주세요.
            </p>
          ) : null}
          <div className="sticky-actions">
            <Link className="button button--secondary" to="/dashboard">
              나중에 하기
            </Link>
            <button
              className="button button--large"
              disabled={createSession.isPending || !leftDeviceId || !rightDeviceId}
              type="submit"
            >
              {createSession.isPending ? (
                <Spinner label="측정 준비 중" />
              ) : (
                <>
                  <Icon name="activity" />
                  준비 완료 및 계속
                </>
              )}
            </button>
          </div>
        </form>
      ) : null}
    </div>
  );
}
