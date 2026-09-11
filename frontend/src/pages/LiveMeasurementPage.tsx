import { useEffect, useId, useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { queryKeys, useDevices, useMeasurement, useSensorLayout } from '../api/queries';
import { measurementApi } from '../api/services';
import type { QualityLevel } from '../api/types';
import { usePreferences } from '../app/preferences';
import { Donut } from '../components/charts';
import { Icon } from '../components/Icon';
import { SessionIdCopy } from '../components/SessionIdCopy';
import { ErrorPanel, Spinner, StatePanel, StatusBadge } from '../components/StatusUi';
import { ReauthDialog } from '../features/auth/ReauthDialog';
import { FootPressureHeatmap, PressureLegend } from '../features/realtime/FootPressureHeatmap';
import { sensorLayoutStatus } from '../features/realtime/layoutStatus';
import {
  useRealtimeMeasurement,
  type RealtimeConnectionStatus,
} from '../features/realtime/useRealtimeMeasurement';
import { formatDuration, formatPercent } from '../utils/format';
import {
  qualityFlagLabel,
  qualityLabels,
  receiverStatusHint,
  resultTerms,
  sessionSourceBadge,
} from '../utils/labels';

const connectionLabel: Record<RealtimeConnectionStatus, string> = {
  IDLE: '대기',
  CONNECTING: '연결 중',
  CONNECTED: '연결됨',
  RECONNECTING: '연결 복구 중',
  DISCONNECTED: '연결 끊김',
  AUTH_EXPIRED: '로그인 만료',
  ERROR: '연결 오류',
};

const connectionTone = (status: RealtimeConnectionStatus) =>
  status === 'CONNECTED'
    ? ('positive' as const)
    : status === 'ERROR' || status === 'DISCONNECTED' || status === 'AUTH_EXPIRED'
      ? ('danger' as const)
      : ('warning' as const);

const qualityTone = (quality: QualityLevel | undefined) =>
  quality === 'GOOD'
    ? ('positive' as const)
    : quality === 'POOR'
      ? ('danger' as const)
      : quality
        ? ('warning' as const)
        : ('neutral' as const);

// 정강이 움직임(기능 검증용) 기준 자세 프로토콜: 시작 직후 2초 정지 구간을 백엔드가 자동으로 잡는다(DEC-036).
const STANDING_PROTOCOL_SECONDS = 2;
const STANDING_PROTOCOL_TITLE = `정강이 움직임 기준 자세: ${STANDING_PROTOCOL_SECONDS}초간 가만히 서 있어 주세요`;
const STANDING_PROTOCOL_HINT =
  '정강이 IMU 기준 자세는 측정 시작 직후 정지 구간에서 자동으로 잡힙니다.';
const STANDING_COUNTDOWN_TICK_MS = 1_000;
const STANDING_COUNTDOWN_DONE_MS = 1_500;

const SIMULATED_NOTICE =
  '시뮬레이션 세션입니다. 실기기 데이터가 아니며 기록에도 시뮬레이션으로 표시됩니다.';

// 2 → 1 → 완료(0) → 숨김(null). window.setTimeout 기반이라 테스트에서 fake timer로 진행할 수 있다.
function useStandingCountdown() {
  const [remaining, setRemaining] = useState<number | null>(null);

  useEffect(() => {
    if (remaining === null) return;
    const delay = remaining > 0 ? STANDING_COUNTDOWN_TICK_MS : STANDING_COUNTDOWN_DONE_MS;
    const timer = window.setTimeout(() => {
      setRemaining((current) => (current === null || current <= 0 ? null : current - 1));
    }, delay);
    return () => window.clearTimeout(timer);
  }, [remaining]);

  return { remaining, begin: () => setRemaining(STANDING_PROTOCOL_SECONDS) };
}

function StandingCountdown({
  remaining,
  reduceMotion,
}: {
  remaining: number;
  reduceMotion: boolean;
}) {
  const titleId = useId();
  const done = remaining <= 0;
  const progress = 1 - remaining / STANDING_PROTOCOL_SECONDS;
  // 카드 전체가 아니라 남은 시간 문장만 live region으로 두어 틱마다 제목·eyebrow를 다시 읽지 않게 한다.
  return (
    <section
      aria-labelledby={titleId}
      className={`standing-countdown${done ? ' standing-countdown--done' : ''}${
        reduceMotion ? ' standing-countdown--static' : ''
      }`}
    >
      <div className="standing-countdown__ring" aria-hidden="true">
        <svg viewBox="0 0 120 120">
          <circle className="standing-countdown__track" cx="60" cy="60" r="52" />
          <circle
            className="standing-countdown__arc"
            cx="60"
            cy="60"
            pathLength="100"
            r="52"
            strokeDasharray={`${Math.round(progress * 100)} ${Math.round((1 - progress) * 100)}`}
            style={
              reduceMotion ? undefined : { transitionDuration: `${STANDING_COUNTDOWN_TICK_MS}ms` }
            }
          />
        </svg>
        <strong className="standing-countdown__count">{done ? '완료' : remaining}</strong>
      </div>
      <div className="standing-countdown__body">
        <p aria-hidden="true" className="standing-countdown__eyebrow">
          STANDING REFERENCE
        </p>
        <h2 id={titleId}>{STANDING_PROTOCOL_TITLE}</h2>
        <p role="status">
          {done
            ? '기준 자세 구간이 끝났습니다. 이제 평소처럼 걸어 주세요.'
            : `남은 시간 ${remaining}초 · 양발을 바닥에 두고 움직이지 않습니다.`}
        </p>
      </div>
    </section>
  );
}

export function LiveMeasurementPage() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // MEASURING 동안 5초마다 재조회해 수신기 업로드 상태(receiverState/receiverPendingBatches)를 갱신한다.
  const measurement = useMeasurement(sessionId, { pollWhileMeasuring: true });
  const devices = useDevices();
  const live = useRealtimeMeasurement(sessionId ?? '', measurement.data?.status === 'MEASURING');
  const [reauthOpen, setReauthOpen] = useState(false);
  const [{ reduceMotion }] = usePreferences();
  const countdown = useStandingCountdown();
  const balanceTitleId = useId();

  const leftDevice = devices.data?.find(
    (device) => device.deviceId === measurement.data?.leftDeviceId,
  );
  const rightDevice = devices.data?.find(
    (device) => device.deviceId === measurement.data?.rightDeviceId,
  );
  const leftLayout = useSensorLayout(leftDevice?.sensorLayoutVersion);
  const rightLayout = useSensorLayout(rightDevice?.sensorLayoutVersion);
  const leftLayoutStatus = sensorLayoutStatus({ layout: leftLayout, devices, device: leftDevice });
  const rightLayoutStatus = sensorLayoutStatus({
    layout: rightLayout,
    devices,
    device: rightDevice,
  });

  const reconcileControlOutcome = async () => {
    if (!sessionId) return;
    try {
      const latest = await measurementApi.get(sessionId);
      queryClient.setQueryData(queryKeys.measurement(sessionId), latest);
      if (latest.status === 'PROCESSING' || latest.status === 'COMPLETED') {
        await queryClient.invalidateQueries({ queryKey: ['measurements'] });
        void navigate(`/measurements/${sessionId}/result`, { replace: true });
      } else if (latest.status === 'CANCELLED') {
        await queryClient.invalidateQueries({ queryKey: ['measurements'] });
        void navigate('/dashboard', { replace: true });
      }
    } catch {
      await queryClient.invalidateQueries({ queryKey: queryKeys.measurement(sessionId) });
    }
  };

  const complete = useMutation({
    mutationFn: () => measurementApi.complete(sessionId ?? ''),
    onSuccess: async () => {
      if (!sessionId) return;
      await queryClient.invalidateQueries({ queryKey: queryKeys.measurement(sessionId) });
      await queryClient.invalidateQueries({ queryKey: ['measurements'] });
      void navigate(`/measurements/${sessionId}/result`, { replace: true });
    },
    onError: () => {
      void reconcileControlOutcome();
    },
  });
  const cancel = useMutation({
    mutationFn: () => measurementApi.cancel(sessionId ?? ''),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['measurements'] });
      void navigate('/dashboard', { replace: true });
    },
    onError: () => {
      void reconcileControlOutcome();
    },
  });
  const start = useMutation({
    mutationFn: () => measurementApi.start(sessionId ?? ''),
    onSuccess: (session) => {
      // 시작 응답(MEASURING)을 바로 캐시에 반영해 정지 구간 카운트다운이 실시간 화면과 함께 뜨게 한다.
      if (sessionId) queryClient.setQueryData(queryKeys.measurement(sessionId), session);
      countdown.begin();
    },
    onSettled: () => {
      if (sessionId)
        void queryClient.invalidateQueries({ queryKey: queryKeys.measurement(sessionId) });
    },
  });

  const balance = useMemo(() => {
    const leftPressure = live.left?.totalPressure ?? 0;
    const rightPressure = live.right?.totalPressure ?? 0;
    const total = leftPressure + rightPressure;
    return total > 0 ? { left: leftPressure / total, right: rightPressure / total } : null;
  }, [live.left?.totalPressure, live.right?.totalPressure]);

  if (!sessionId)
    return (
      <StatePanel
        title="측정 ID가 없습니다"
        description="기록에서 측정을 다시 선택해 주세요."
        action={
          <Link className="button" to="/history">
            기록으로
          </Link>
        }
      />
    );
  if (measurement.isPending || devices.isPending)
    return (
      <div className="centered-status">
        <Spinner label="측정 상태 확인 중" />
      </div>
    );
  if (measurement.isError || devices.isError)
    return (
      <ErrorPanel
        error={measurement.error ?? devices.error}
        retry={() => {
          void Promise.all([measurement.refetch(), devices.refetch()]);
        }}
      />
    );
  if (measurement.data.status === 'CREATED') {
    return (
      <div className="page-stack live-ready">
        <StatePanel
          icon="activity"
          title="측정을 시작할 준비가 되었어요"
          description={
            start.isError
              ? `${start.error.message} 같은 측정에서 다시 시도할 수 있습니다.`
              : `${sessionSourceBadge(measurement.data)} 세션입니다. 수신기를 이 세션 ID로 실행한 뒤 인솔 연결 상태를 확인하고 시작해 주세요.`
          }
          action={
            <>
              <button className="button" disabled={start.isPending} onClick={() => start.mutate()}>
                {start.isPending ? (
                  <Spinner label="시작 중" />
                ) : start.isError ? (
                  '측정 시작 다시 시도'
                ) : (
                  '측정 시작'
                )}
              </button>
              <SessionIdCopy sessionId={sessionId} />
            </>
          }
        />
        <p className="live-protocol-hint">
          <Icon name="clock" />
          <span>
            <strong>측정을 시작하면 {STANDING_PROTOCOL_SECONDS}초간 가만히 서 있어 주세요.</strong>{' '}
            {STANDING_PROTOCOL_HINT}
          </span>
        </p>
        {measurement.data.sourceType === 'SIMULATED' ? (
          <p className="notice notice--info" role="status">
            <Icon name="alert" />
            {SIMULATED_NOTICE}
          </p>
        ) : null}
      </div>
    );
  }
  if (measurement.data.status === 'PROCESSING' || measurement.data.status === 'COMPLETED') {
    return (
      <StatePanel
        icon="sparkles"
        title={
          measurement.data.status === 'PROCESSING' ? '분석이 진행 중이에요' : '측정이 완료되었어요'
        }
        description="결과 화면에서 진행 상태와 분석 내용을 확인하세요."
        action={
          <Link className="button" to={`/measurements/${sessionId}/result`}>
            결과 확인
          </Link>
        }
      />
    );
  }
  if (measurement.data.status === 'CANCELLED' || measurement.data.status === 'FAILED') {
    return (
      <StatePanel
        title={
          measurement.data.status === 'CANCELLED' ? '취소된 측정입니다' : '측정을 완료하지 못했어요'
        }
        description={
          measurement.data.status === 'FAILED'
            ? '인솔 연결을 확인한 뒤 새 측정을 시작해 주세요.'
            : '필요할 때 새 측정을 시작할 수 있습니다.'
        }
        action={
          <Link className="button" to="/measurements/new">
            새 측정
          </Link>
        }
      />
    );
  }

  const quality = live.message?.quality;
  const actionPending = complete.isPending || cancel.isPending;
  const centerLabel = balance
    ? `${Math.round(balance.left * 100)} : ${Math.round(balance.right * 100)}`
    : '—';
  return (
    <div className="live-page">
      <header className="live-header live-topbar">
        <div className="live-topbar__title">
          <p className="eyebrow">LIVE MEASUREMENT</p>
          <div className="live-title">
            <span className="live-dot" aria-hidden="true" />
            <h1>측정 중</h1>
            <strong className="live-elapsed">
              {formatDuration(live.message?.elapsedTimeMs ?? 0)}
            </strong>
          </div>
        </div>
        <div className="live-statuses live-status-strip">
          <StatusBadge tone={connectionTone(live.connectionStatus)}>
            <span className="badge-dot" />
            연결 {connectionLabel[live.connectionStatus]}
          </StatusBadge>
          <StatusBadge tone={qualityTone(quality?.level)}>
            품질 {quality ? qualityLabels[quality.level] : '확인 중'}
            {quality ? ` · ${quality.score}` : ''}
          </StatusBadge>
          <StatusBadge tone={measurement.data.sourceType === 'SIMULATED' ? 'neutral' : 'info'}>
            {sessionSourceBadge(measurement.data)}
          </StatusBadge>
        </div>
        {/* 품질 점수는 10~20Hz로 바뀌므로 연결 상태·품질 단계 전이만 낭독한다(점수는 배지에만). */}
        <p className="sr-only" role="status">
          {`연결 ${connectionLabel[live.connectionStatus]} · 품질 ${
            quality ? qualityLabels[quality.level] : '확인 중'
          }`}
        </p>
      </header>

      {countdown.remaining !== null ? (
        <StandingCountdown reduceMotion={reduceMotion} remaining={countdown.remaining} />
      ) : null}
      <p className="live-protocol-hint">
        <Icon name="clock" />
        <span>{STANDING_PROTOCOL_HINT}</span>
      </p>

      {measurement.data.sourceType === 'SIMULATED' ? (
        <p className="notice notice--info" role="status">
          <Icon name="alert" />
          {SIMULATED_NOTICE}
        </p>
      ) : null}
      <SessionIdCopy sessionId={sessionId} />

      {live.connectionStatus === 'AUTH_EXPIRED' ? (
        <div className="realtime-notice" role="alert">
          <Icon name="alert" />
          <div>
            <strong>로그인 세션이 만료되어 실시간 연결이 끊겼어요.</strong>
            <p>{live.error}</p>
            <button
              className="button button--compact"
              onClick={() => setReauthOpen(true)}
              type="button"
            >
              다시 로그인
            </button>
          </div>
        </div>
      ) : live.error || live.dataStale ? (
        <div className="realtime-notice" role="alert">
          <Icon name="alert" />
          <div>
            <strong>
              {live.dataStale ? '센서 데이터가 잠시 멈췄어요.' : '실시간 연결을 확인하고 있어요.'}
            </strong>
            <p>
              {live.error ??
                'WebSocket은 연결되어 있지만 새 데이터가 없습니다. Receiver와 인솔을 확인해 주세요.'}
            </p>
          </div>
        </div>
      ) : null}
      <ReauthDialog onClose={() => setReauthOpen(false)} open={reauthOpen} reason="EXPIRED" />
      {quality?.flags.length ? (
        <div className="quality-flags" role="group" aria-label="데이터 품질 알림">
          {quality.flags.map((flag) => (
            <p key={flag}>
              <Icon name="alert" />
              {qualityFlagLabel(flag)}
            </p>
          ))}
        </div>
      ) : null}

      <section className="heatmap-section live-heatmaps" aria-label="양발 실시간 센서 신호">
        <FootPressureHeatmap
          data={live.left}
          disconnected={live.leftDisconnected || live.dataStale}
          layout={leftLayout.data}
          layoutStatus={leftLayoutStatus}
          side="LEFT"
        />
        <FootPressureHeatmap
          data={live.right}
          disconnected={live.rightDisconnected || live.dataStale}
          layout={rightLayout.data}
          layoutStatus={rightLayoutStatus}
          side="RIGHT"
        />
        <aside className="live-legend">
          <PressureLegend orientation="vertical" />
        </aside>
      </section>
      {leftLayout.isError || rightLayout.isError ? (
        <p className="form-error" role="alert">
          <Icon name="alert" />
          센서 배치를 불러오지 못했습니다. 히트맵을 정확히 표시하려면 페이지를 새로고침해 주세요.
        </p>
      ) : null}

      <section
        className="live-comparison live-balance content-card"
        aria-labelledby={balanceTitleId}
      >
        <div className="live-balance__intro">
          <p className="eyebrow">BILATERAL BALANCE</p>
          <h2 id={balanceTitleId}>현재 좌우 신호 비율</h2>
          <p>한 시점의 상대 총 신호 비교이며 의료적 판단 기준이 아닙니다.</p>
        </div>
        <div className="live-balance__donut">
          <Donut
            ariaLabel={
              balance
                ? `왼발 ${formatPercent(balance.left)}, 오른발 ${formatPercent(balance.right)}`
                : '아직 좌우 신호 비율을 계산할 수 없습니다.'
            }
            centerLabel={centerLabel}
            leftPct={balance ? balance.left * 100 : 0}
            rightPct={balance ? balance.right * 100 : 0}
            size={132}
          />
        </div>
        <dl className="live-balance__peaks session-peak">
          <div>
            <dt>세션 {resultTerms.peakSignal} · L</dt>
            <dd>{live.leftPeak === null ? '—' : Math.round(live.leftPeak)}</dd>
          </div>
          <div>
            <dt>세션 {resultTerms.peakSignal} · R</dt>
            <dd>{live.rightPeak === null ? '—' : Math.round(live.rightPeak)}</dd>
          </div>
          <small>0–100 상대값, 세션 시작 이후 누적</small>
        </dl>
      </section>

      {complete.isError || cancel.isError ? (
        <p className="form-error" role="alert">
          <Icon name="alert" />
          {(complete.error ?? cancel.error)?.message} 측정 상태를 확인한 뒤 다시 시도해 주세요.
        </p>
      ) : null}
      <section className="measurement-controls" aria-label="측정 제어">
        <div>
          <strong>안전하게 측정하고 있나요?</strong>
          <p>통증이나 불편함이 있으면 즉시 종료하세요.</p>
          <p className="receiver-hint">{receiverStatusHint(measurement.data)}</p>
        </div>
        <div>
          <button
            className="button button--ghost-danger"
            disabled={actionPending}
            onClick={() => {
              if (window.confirm('현재 측정을 취소할까요? 저장된 데이터는 분석되지 않습니다.'))
                cancel.mutate();
            }}
          >
            측정 취소
          </button>
          <button
            className="button button--large"
            disabled={actionPending}
            onClick={() => complete.mutate()}
          >
            {complete.isPending ? (
              <Spinner label="종료 중" />
            ) : (
              <>
                <Icon name="check" />
                측정 종료
              </>
            )}
          </button>
        </div>
      </section>
    </div>
  );
}
