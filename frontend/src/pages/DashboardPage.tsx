import { Link } from 'react-router-dom';
import { useDevices, useMeasurements } from '../api/queries';
import { DeviceCard } from '../components/DeviceCard';
import { Icon } from '../components/Icon';
import { ErrorPanel, PageHeader, Spinner, StatePanel, StatusBadge } from '../components/StatusUi';
import { useAuth } from '../features/auth/AuthContext';
import { formatDateTime, formatDuration } from '../utils/format';
import { measurementStatusLabels } from '../utils/labels';

export function DashboardPage() {
  const { user } = useAuth();
  const devices = useDevices();
  const measurements = useMeasurements({ page: 0, size: 5 });

  return (
    <div className="page-stack dashboard-page">
      <PageHeader eyebrow="오늘도 안전하게" title={`${user?.name ?? '사용자'}님의 걸음을 살펴볼까요?`} description="인솔 연결을 확인하고 새로운 측정을 시작해 보세요." action={<Link className="button button--large" to="/measurements/new"><Icon name="plus" />새 측정</Link>} />

      <section className="hero-card">
        <div className="hero-card__content">
          <span className="hero-card__icon"><Icon name="activity" /></span>
          <div><p className="eyebrow eyebrow--light">QUICK START</p><h2>두 인솔을 연결한 뒤 평소처럼 걸어보세요.</h2><p>측정 중에는 양발 압력과 데이터 품질을 실시간으로 확인할 수 있습니다.</p></div>
        </div>
        <Link className="button button--light" to="/measurements/new">측정 준비하기<Icon name="arrow" /></Link>
      </section>

      <div className="dashboard-grid">
        <section className="content-card dashboard-devices" aria-labelledby="device-summary-title">
          <div className="section-heading"><div><p className="eyebrow">MY INSOLES</p><h2 id="device-summary-title">내 인솔</h2></div><Link className="text-link" to="/devices">전체 보기<Icon name="arrow" /></Link></div>
          {devices.isPending ? <Spinner label="인솔 확인 중" /> : null}
          {devices.isError ? <ErrorPanel error={devices.error} retry={() => void devices.refetch()} /> : null}
          {devices.data?.length === 0 ? <StatePanel compact icon="device" title="등록된 인솔이 없어요" description="왼발과 오른발 인솔을 등록한 뒤 측정을 시작할 수 있습니다." action={<Link className="button button--secondary" to="/devices">인솔 등록</Link>} /> : null}
          {devices.data ? <div className="compact-device-list">{devices.data.slice(0, 2).map((device) => <DeviceCard compact device={device} key={device.deviceId} />)}</div> : null}
        </section>

        <section className="content-card dashboard-history" aria-labelledby="recent-title">
          <div className="section-heading"><div><p className="eyebrow">RECENT ACTIVITY</p><h2 id="recent-title">최근 측정</h2></div><Link className="text-link" to="/history">전체 기록<Icon name="arrow" /></Link></div>
          {measurements.isPending ? <Spinner label="최근 기록 불러오는 중" /> : null}
          {measurements.isError ? <ErrorPanel error={measurements.error} retry={() => void measurements.refetch()} /> : null}
          {measurements.data?.items.length === 0 ? <StatePanel compact icon="history" title="아직 측정 기록이 없어요" description="첫 측정을 완료하면 이곳에서 흐름을 확인할 수 있습니다." /> : null}
          {measurements.data?.items.length ? <ul className="measurement-list">{measurements.data.items.map((session) => {
            const duration = session.startedAt && session.endedAt ? new Date(session.endedAt).getTime() - new Date(session.startedAt).getTime() : null;
            const target = session.status === 'CREATED' || session.status === 'MEASURING' ? `/measurements/${session.sessionId}/live` : `/measurements/${session.sessionId}/result`;
            return <li key={session.sessionId}><Link to={target}><span className="measurement-list__icon"><Icon name={session.status === 'COMPLETED' ? 'check' : 'clock'} /></span><span className="measurement-list__main"><strong>{formatDateTime(session.createdAt)}</strong><small>{duration === null ? '측정 시간 기록 없음' : `${formatDuration(duration)} 측정`}</small></span>{session.dataQualityScore !== null && session.dataQualityScore !== undefined ? <span className="quality-score"><strong>{session.dataQualityScore}</strong><small>품질</small></span> : null}<StatusBadge tone={session.status === 'COMPLETED' ? 'positive' : session.status === 'FAILED' ? 'danger' : 'info'}>{measurementStatusLabels[session.status]}</StatusBadge><Icon name="arrow" /></Link></li>;
          })}</ul> : null}
        </section>
      </div>
    </div>
  );
}
