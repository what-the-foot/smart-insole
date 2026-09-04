import { useMemo } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { queryKeys, useDevices, useMeasurement, useSensorLayout } from '../api/queries';
import { measurementApi } from '../api/services';
import type { QualityLevel } from '../api/types';
import { Icon } from '../components/Icon';
import { ErrorPanel, Spinner, StatePanel, StatusBadge } from '../components/StatusUi';
import { FootPressureHeatmap, PressureLegend } from '../features/realtime/FootPressureHeatmap';
import { useRealtimeMeasurement, type RealtimeConnectionStatus } from '../features/realtime/useRealtimeMeasurement';
import { formatDuration, formatPercent } from '../utils/format';
import { qualityFlagLabel, qualityLabels } from '../utils/labels';

const connectionLabel: Record<RealtimeConnectionStatus, string> = {
  IDLE: '대기', CONNECTING: '연결 중', CONNECTED: '연결됨', RECONNECTING: '연결 복구 중', DISCONNECTED: '연결 끊김', ERROR: '연결 오류',
};

const connectionTone = (status: RealtimeConnectionStatus) =>
  status === 'CONNECTED' ? 'positive' as const : status === 'ERROR' || status === 'DISCONNECTED' ? 'danger' as const : 'warning' as const;

const qualityTone = (quality: QualityLevel | undefined) =>
  quality === 'GOOD' ? 'positive' as const : quality === 'POOR' ? 'danger' as const : quality ? 'warning' as const : 'neutral' as const;

export function LiveMeasurementPage() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const measurement = useMeasurement(sessionId);
  const devices = useDevices();
  const live = useRealtimeMeasurement(sessionId ?? '', measurement.data?.status === 'MEASURING');

  const leftDevice = devices.data?.find((device) => device.deviceId === measurement.data?.leftDeviceId);
  const rightDevice = devices.data?.find((device) => device.deviceId === measurement.data?.rightDeviceId);
  const leftLayout = useSensorLayout(leftDevice?.sensorLayoutVersion);
  const rightLayout = useSensorLayout(rightDevice?.sensorLayoutVersion);

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
    onError: () => { void reconcileControlOutcome(); },
  });
  const cancel = useMutation({
    mutationFn: () => measurementApi.cancel(sessionId ?? ''),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['measurements'] });
      void navigate('/dashboard', { replace: true });
    },
    onError: () => { void reconcileControlOutcome(); },
  });
  const start = useMutation({
    mutationFn: () => measurementApi.start(sessionId ?? ''),
    onSettled: () => {
      if (sessionId) void queryClient.invalidateQueries({ queryKey: queryKeys.measurement(sessionId) });
    },
  });

  const balance = useMemo(() => {
    const leftPressure = live.left?.totalPressure ?? 0;
    const rightPressure = live.right?.totalPressure ?? 0;
    const total = leftPressure + rightPressure;
    return total > 0 ? { left: leftPressure / total, right: rightPressure / total } : null;
  }, [live.left?.totalPressure, live.right?.totalPressure]);

  if (!sessionId) return <StatePanel title="측정 ID가 없습니다" description="기록에서 측정을 다시 선택해 주세요." action={<Link className="button" to="/history">기록으로</Link>} />;
  if (measurement.isPending || devices.isPending) return <div className="centered-status"><Spinner label="측정 상태 확인 중" /></div>;
  if (measurement.isError || devices.isError) return <ErrorPanel error={measurement.error ?? devices.error} retry={() => { void Promise.all([measurement.refetch(), devices.refetch()]); }} />;
  if (measurement.data.status === 'CREATED') {
    return <StatePanel icon="activity" title="측정을 시작할 준비가 되었어요" description={start.isError ? `${start.error.message} 같은 측정에서 다시 시도할 수 있습니다.` : '인솔 연결 상태를 확인한 뒤 시작해 주세요.'} action={<button className="button" disabled={start.isPending} onClick={() => start.mutate()}>{start.isPending ? <Spinner label="시작 중" /> : start.isError ? '측정 시작 다시 시도' : '측정 시작'}</button>} />;
  }
  if (measurement.data.status === 'PROCESSING' || measurement.data.status === 'COMPLETED') {
    return <StatePanel icon="sparkles" title={measurement.data.status === 'PROCESSING' ? '분석이 진행 중이에요' : '측정이 완료되었어요'} description="결과 화면에서 진행 상태와 분석 내용을 확인하세요." action={<Link className="button" to={`/measurements/${sessionId}/result`}>결과 확인</Link>} />;
  }
  if (measurement.data.status === 'CANCELLED' || measurement.data.status === 'FAILED') {
    return <StatePanel title={measurement.data.status === 'CANCELLED' ? '취소된 측정입니다' : '측정을 완료하지 못했어요'} description={measurement.data.status === 'FAILED' ? '인솔 연결을 확인한 뒤 새 측정을 시작해 주세요.' : '필요할 때 새 측정을 시작할 수 있습니다.'} action={<Link className="button" to="/measurements/new">새 측정</Link>} />;
  }

  const quality = live.message?.quality;
  const actionPending = complete.isPending || cancel.isPending;
  return (
    <div className="live-page">
      <header className="live-header">
        <div><p className="eyebrow">LIVE MEASUREMENT</p><div className="live-title"><span className="live-dot" aria-hidden="true" /><h1>측정 중</h1><strong>{formatDuration(live.message?.elapsedTimeMs ?? 0)}</strong></div></div>
        <div className="live-statuses" aria-live="polite">
          <StatusBadge tone={connectionTone(live.connectionStatus)}><span className="badge-dot" />연결 {connectionLabel[live.connectionStatus]}</StatusBadge>
          <StatusBadge tone={qualityTone(quality?.level)}>품질 {quality ? qualityLabels[quality.level] : '확인 중'}{quality ? ` · ${quality.score}` : ''}</StatusBadge>
        </div>
      </header>

      {(live.error || live.dataStale) ? <div className="realtime-notice" role="alert"><Icon name="alert" /><div><strong>{live.dataStale ? '센서 데이터가 잠시 멈췄어요.' : '실시간 연결을 확인하고 있어요.'}</strong><p>{live.error ?? 'WebSocket은 연결되어 있지만 새 데이터가 없습니다. Receiver와 인솔을 확인해 주세요.'}</p></div></div> : null}
      {quality?.flags.length ? <div className="quality-flags" aria-label="데이터 품질 알림">{quality.flags.map((flag) => <p key={flag}><Icon name="alert" />{qualityFlagLabel(flag)}</p>)}</div> : null}

      <section className="heatmap-section" aria-label="양발 실시간 족압">
        <FootPressureHeatmap data={live.left} disconnected={live.leftDisconnected || live.dataStale} layout={leftLayout.data} side="LEFT" />
        <FootPressureHeatmap data={live.right} disconnected={live.rightDisconnected || live.dataStale} layout={rightLayout.data} side="RIGHT" />
      </section>
      {(leftLayout.isError || rightLayout.isError) ? <p className="form-error" role="alert"><Icon name="alert" />센서 배치를 불러오지 못했습니다. 히트맵을 정확히 표시하려면 페이지를 새로고침해 주세요.</p> : null}

      <section className="live-comparison content-card" aria-labelledby="balance-title">
        <div><p className="eyebrow">BILATERAL BALANCE</p><h2 id="balance-title">현재 좌우 압력 비율</h2><p>한 시점의 상대값이며 의료적 판단 기준이 아닙니다.</p></div>
        <div className="balance-meter" aria-label={balance ? `왼발 ${formatPercent(balance.left)}, 오른발 ${formatPercent(balance.right)}` : '아직 좌우 압력 비율을 계산할 수 없습니다.'}>
          <div className="balance-meter__labels"><strong>L {balance ? formatPercent(balance.left) : '—'}</strong><strong>R {balance ? formatPercent(balance.right) : '—'}</strong></div>
          <div className="balance-meter__track" aria-hidden="true"><span style={{ width: `${(balance?.left ?? 0.5) * 100}%` }} /></div>
        </div>
        <PressureLegend />
      </section>

      {(complete.isError || cancel.isError) ? <p className="form-error" role="alert"><Icon name="alert" />{(complete.error ?? cancel.error)?.message} 측정 상태를 확인한 뒤 다시 시도해 주세요.</p> : null}
      <div className="measurement-controls" aria-label="측정 제어">
        <div><strong>안전하게 측정하고 있나요?</strong><p>통증이나 불편함이 있으면 즉시 종료하세요.</p></div>
        <div><button className="button button--ghost-danger" disabled={actionPending} onClick={() => { if (window.confirm('현재 측정을 취소할까요? 저장된 데이터는 분석되지 않습니다.')) cancel.mutate(); }}>측정 취소</button><button className="button button--large" disabled={actionPending} onClick={() => complete.mutate()}>{complete.isPending ? <Spinner label="종료 중" /> : <><Icon name="check" />측정 종료</>}</button></div>
      </div>
    </div>
  );
}
