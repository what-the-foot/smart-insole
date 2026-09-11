import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/Icon';
import { PageHeader, StatusBadge } from '../components/StatusUi';
import { useAuth } from '../features/auth/AuthContext';
import { ReauthDialog } from '../features/auth/ReauthDialog';
import { formatRemaining, useSessionExpiry } from '../features/auth/useSessionExpiry';
import { SAMPLE_RATE_OPTIONS, sampleRateLabels } from '../features/measurement/sampleRates';
import { formatDateTime } from '../utils/format';
import { heatmapModeLabels, usePreferences, type HeatmapMode } from '../app/preferences';

const heatmapModes: readonly HeatmapMode[] = ['continuous', 'points'];

// 이름의 첫 글자(아바타). 이름이 비어 있으면 '?'.
const initialsOf = (name: string | undefined): string => {
  const trimmed = name?.trim() ?? '';
  return trimmed ? trimmed.slice(0, 1) : '?';
};

// 프론트 전용 설정(v1). 프로필·비밀번호 변경 API는 계약에 없어 계정 정보는 읽기 전용으로만 보여준다.
export function SettingsPage() {
  const { user, signout } = useAuth();
  const navigate = useNavigate();
  const expiry = useSessionExpiry();
  const [preferences, updatePreferences] = usePreferences();
  const [reauthOpen, setReauthOpen] = useState(false);

  const handleSignout = () => {
    signout();
    void navigate('/login', { replace: true });
  };

  return (
    <div className="page-stack settings-page">
      <PageHeader
        eyebrow="SETTINGS"
        title="설정"
        description="계정 정보와 이 브라우저에서만 적용되는 화면 설정을 관리합니다."
      />

      <div className="settings-layout">
        <section
          className="content-card settings-card settings-card--account"
          aria-labelledby="account-title"
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">ACCOUNT</p>
              <h2 id="account-title">계정</h2>
              <p>
                계정 정보 변경은 아직 지원하지 않습니다. 변경이 필요하면 관리자에게 문의해 주세요.
              </p>
            </div>
            <StatusBadge tone="neutral">읽기 전용</StatusBadge>
          </div>
          <div className="settings-profile">
            <span aria-hidden="true" className="settings-profile__avatar">
              {initialsOf(user?.name)}
            </span>
            <dl className="settings-meta">
              <div>
                <dt>이름</dt>
                <dd>{user?.name ?? '-'}</dd>
              </div>
              <div>
                <dt>이메일</dt>
                <dd>{user?.email ?? '-'}</dd>
              </div>
              <div>
                <dt>가입일</dt>
                <dd>{formatDateTime(user?.createdAt)}</dd>
              </div>
            </dl>
          </div>
        </section>

        <section
          className="content-card settings-card settings-card--session"
          aria-labelledby="session-title"
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">SESSION</p>
              <h2 id="session-title">로그인 세션</h2>
              <p>브라우저 탭을 닫으면 로그인 정보가 삭제됩니다.</p>
            </div>
          </div>
          <dl className="settings-meta">
            <div>
              <dt>만료 시각</dt>
              <dd>
                {expiry.expiresAt === null
                  ? '-'
                  : formatDateTime(new Date(expiry.expiresAt).toISOString())}
              </dd>
            </div>
            <div>
              <dt>남은 시간</dt>
              <dd>{expiry.remainingMs === null ? '-' : formatRemaining(expiry.remainingMs)}</dd>
            </div>
          </dl>
          <div className="form-actions settings-actions">
            <button
              className="button button--secondary"
              onClick={() => setReauthOpen(true)}
              type="button"
            >
              <Icon name="clock" />
              다시 로그인
            </button>
            <button className="button button--ghost-danger" onClick={handleSignout} type="button">
              <Icon name="logout" />
              로그아웃
            </button>
          </div>
        </section>

        <section
          className="content-card settings-card settings-card--preferences"
          aria-labelledby="preferences-title"
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">DISPLAY</p>
              <h2 id="preferences-title">화면 설정</h2>
              <p>이 브라우저에만 저장되며 다른 기기와 동기화되지 않습니다.</p>
            </div>
            <StatusBadge tone="info">이 브라우저에만 저장</StatusBadge>
          </div>
          <div className="settings-groups">
            <fieldset className="settings-fieldset">
              <legend>기본 전송률</legend>
              <p className="muted">새 측정을 준비할 때 먼저 선택되는 전송률입니다.</p>
              <div className="settings-options">
                {SAMPLE_RATE_OPTIONS.map((rate) => (
                  <label
                    className={`selector-option${preferences.defaultSampleRateHz === rate ? ' selector-option--selected' : ''}`}
                    key={rate}
                  >
                    <input
                      checked={preferences.defaultSampleRateHz === rate}
                      name="defaultSampleRateHz"
                      onChange={() => updatePreferences({ defaultSampleRateHz: rate })}
                      type="radio"
                      value={rate}
                    />
                    <span>
                      {sampleRateLabels[rate].title}
                      <small>{sampleRateLabels[rate].description}</small>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            <fieldset className="settings-fieldset">
              <legend>히트맵 표시 방식</legend>
              <p className="muted">실시간·결과 화면의 양발 히트맵을 그리는 방식입니다.</p>
              <div className="settings-options">
                {heatmapModes.map((mode) => (
                  <label
                    className={`selector-option${preferences.heatmapMode === mode ? ' selector-option--selected' : ''}`}
                    key={mode}
                  >
                    <input
                      checked={preferences.heatmapMode === mode}
                      name="heatmapMode"
                      onChange={() => updatePreferences({ heatmapMode: mode })}
                      type="radio"
                      value={mode}
                    />
                    <span>
                      {heatmapModeLabels[mode].title}
                      <small>{heatmapModeLabels[mode].description}</small>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            <label className="checkbox-field settings-toggle">
              <input
                checked={preferences.reduceMotion}
                onChange={(event) => updatePreferences({ reduceMotion: event.target.checked })}
                type="checkbox"
              />
              <span>
                애니메이션 줄이기
                <small>실시간 표시와 화면 전환의 움직임을 최소화합니다.</small>
              </span>
            </label>
          </div>
        </section>
      </div>
      <ReauthDialog onClose={() => setReauthOpen(false)} open={reauthOpen} reason="EXPIRING" />
    </div>
  );
}
