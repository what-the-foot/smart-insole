import type { AuthTokenResponse, UserSummary } from '../../api/types';
import { queryClient } from '../../api/queryClient';

const STORAGE_KEY = 'smart-insole.auth.v1';
const SIGNOUT_REASON_KEY = 'smart-insole.auth.signout-reason.v1';
const AUTH_EVENT = 'smart-insole:auth-change';

export interface AuthSession {
  accessToken: string;
  expiresAt: number;
  user: UserSummary;
}

// 로그인 화면 복귀 안내용. EXPIRED: 토큰 수명 만료(로컬 타이머·STOMP TOKEN_EXPIRED), UNAUTHORIZED: REST 401.
export type SignoutReason = 'EXPIRED' | 'UNAUTHORIZED' | 'USER';

const signoutReasons: readonly SignoutReason[] = ['EXPIRED', 'UNAUTHORIZED', 'USER'];

const isUserSummary = (value: unknown): value is UserSummary => {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.userId === 'string' &&
    typeof candidate.email === 'string' &&
    typeof candidate.name === 'string' &&
    typeof candidate.createdAt === 'string'
  );
};

const isAuthSession = (value: unknown): value is AuthSession => {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.accessToken === 'string' &&
    typeof candidate.expiresAt === 'number' &&
    isUserSummary(candidate.user)
  );
};

const notify = (): void => {
  window.dispatchEvent(new Event(AUTH_EVENT));
};

const purgeUserState = (reason: SignoutReason): void => {
  window.sessionStorage.removeItem(STORAGE_KEY);
  window.sessionStorage.setItem(SIGNOUT_REASON_KEY, reason);
  queryClient.clear();
};

export const readAuthSession = (): AuthSession | null => {
  const serialized = window.sessionStorage.getItem(STORAGE_KEY);
  if (!serialized) return null;

  try {
    const parsed: unknown = JSON.parse(serialized);
    if (!isAuthSession(parsed)) {
      purgeUserState('UNAUTHORIZED');
      notify();
      return null;
    }
    if (parsed.expiresAt <= Date.now()) {
      purgeUserState('EXPIRED');
      notify();
      return null;
    }
    return parsed;
  } catch {
    purgeUserState('UNAUTHORIZED');
    notify();
    return null;
  }
};

// 저장 + 알림을 한 번에 처리한다. AuthProvider는 구독 콜백으로만 상태를 갱신하므로 이중 갱신이 없다.
export const saveAuthResponse = (response: AuthTokenResponse): AuthSession => {
  const session: AuthSession = {
    accessToken: response.accessToken,
    expiresAt: Date.now() + response.expiresInSeconds * 1000,
    user: response.user,
  };
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  window.sessionStorage.removeItem(SIGNOUT_REASON_KEY);
  notify();
  return session;
};

export const clearAuthSession = (reason: SignoutReason = 'USER'): void => {
  purgeUserState(reason);
  notify();
};

// 한 번 읽으면 지운다(로그인 화면에서 안내를 한 번만 보여주기 위함).
export const consumeLastSignoutReason = (): SignoutReason | null => {
  const stored = window.sessionStorage.getItem(SIGNOUT_REASON_KEY);
  window.sessionStorage.removeItem(SIGNOUT_REASON_KEY);
  return signoutReasons.find((reason) => reason === stored) ?? null;
};

export const subscribeToAuthSession = (listener: () => void): (() => void) => {
  window.addEventListener(AUTH_EVENT, listener);
  window.addEventListener('storage', listener);
  return () => {
    window.removeEventListener(AUTH_EVENT, listener);
    window.removeEventListener('storage', listener);
  };
};
