import { useState } from 'react';
import { ReauthDialog } from '../features/auth/ReauthDialog';
import { formatRemaining, useSessionExpiry } from '../features/auth/useSessionExpiry';
import { Icon } from './Icon';

// 만료 5분 전부터 표시. 만료 순간에는 AuthProvider가 세션을 정리해 ProtectedRoute가 로그인 화면으로 보낸다.
export function SessionExpiryBanner() {
  const expiry = useSessionExpiry();
  const [dialogOpen, setDialogOpen] = useState(false);

  if (!expiry.warning || expiry.remainingMs === null) return null;

  return (
    <>
      <div className="session-expiry-banner" role="status">
        <Icon name="clock" />
        <p>
          <strong>로그인 세션이 {formatRemaining(expiry.remainingMs)} 후 만료됩니다.</strong>
          <span> 만료되면 로그인 화면으로 이동하며 진행 중인 측정 화면의 실시간 연결이 끊깁니다. 지금 다시 로그인하면 이어서 볼 수 있습니다.</span>
        </p>
        <button className="button button--compact" onClick={() => setDialogOpen(true)} type="button">다시 로그인</button>
      </div>
      <ReauthDialog onClose={() => setDialogOpen(false)} open={dialogOpen} reason="EXPIRING" />
    </>
  );
}
