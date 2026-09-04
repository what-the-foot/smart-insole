import type { AuthTokenResponse, UserSummary } from '../../api/types';
import { queryClient } from '../../api/queryClient';

const STORAGE_KEY = 'smart-insole.auth.v1';
const AUTH_EVENT = 'smart-insole:auth-change';

export interface AuthSession {
  accessToken: string;
  expiresAt: number;
  user: UserSummary;
}

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

const purgeUserState = (): void => {
  window.sessionStorage.removeItem(STORAGE_KEY);
  queryClient.clear();
};

export const readAuthSession = (): AuthSession | null => {
  const serialized = window.sessionStorage.getItem(STORAGE_KEY);
  if (!serialized) return null;

  try {
    const parsed: unknown = JSON.parse(serialized);
    if (!isAuthSession(parsed) || parsed.expiresAt <= Date.now()) {
      purgeUserState();
      notify();
      return null;
    }
    return parsed;
  } catch {
    purgeUserState();
    notify();
    return null;
  }
};

export const saveAuthResponse = (response: AuthTokenResponse): AuthSession => {
  const session: AuthSession = {
    accessToken: response.accessToken,
    expiresAt: Date.now() + response.expiresInSeconds * 1000,
    user: response.user,
  };
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  notify();
  return session;
};

export const clearAuthSession = (): void => {
  purgeUserState();
  notify();
};

export const subscribeToAuthSession = (listener: () => void): (() => void) => {
  window.addEventListener(AUTH_EVENT, listener);
  window.addEventListener('storage', listener);
  return () => {
    window.removeEventListener(AUTH_EVENT, listener);
    window.removeEventListener('storage', listener);
  };
};
