import { useEffect, useState } from 'react';
import { useAuth } from './AuthContext';

// 만료 5분 전부터 배너를 띄운다(토큰 수명 3600초 유지, 리프레시 API는 후속 — 계획 §6 토큰 정책).
export const SESSION_EXPIRY_WARNING_MS = 5 * 60_000;
const TICK_MS = 1_000;
const MAX_TIMER_MS = 2_147_483_647;

export interface SessionExpiryState {
  expiresAt: number | null;
  remainingMs: number | null;
  /** 만료 5분 전 이내이고 아직 만료되지 않음 */
  warning: boolean;
  expired: boolean;
}

export function useSessionExpiry(): SessionExpiryState {
  const { session } = useAuth();
  const [now, setNow] = useState(() => Date.now());
  const inWarningWindow = session !== null && session.expiresAt - SESSION_EXPIRY_WARNING_MS <= now;

  useEffect(() => {
    if (!session) return;
    // 경고 구간 전에는 경고 시작 시각까지 한 번만 깨어나고, 경고 구간에서는 1초 간격으로 갱신한다.
    if (!inWarningWindow) {
      const untilWarning = Math.max(0, session.expiresAt - SESSION_EXPIRY_WARNING_MS - Date.now());
      const timer = window.setTimeout(() => setNow(Date.now()), Math.min(untilWarning, MAX_TIMER_MS));
      return () => window.clearTimeout(timer);
    }
    const interval = window.setInterval(() => setNow(Date.now()), TICK_MS);
    return () => window.clearInterval(interval);
  }, [session, inWarningWindow]);

  if (!session) return { expiresAt: null, remainingMs: null, warning: false, expired: false };

  const remainingMs = Math.max(0, session.expiresAt - now);
  return {
    expiresAt: session.expiresAt,
    remainingMs,
    warning: remainingMs > 0 && remainingMs <= SESSION_EXPIRY_WARNING_MS,
    expired: remainingMs === 0,
  };
}

export const formatRemaining = (remainingMs: number): string => {
  const totalSeconds = Math.ceil(remainingMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes > 0) return seconds > 0 ? `${minutes}분 ${seconds}초` : `${minutes}분`;
  return `${seconds}초`;
};
