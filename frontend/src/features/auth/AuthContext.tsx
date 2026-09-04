import { createContext, use, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { authApi } from '../../api/services';
import type { SigninRequest, SignupRequest, UserSummary } from '../../api/types';
import {
  clearAuthSession,
  readAuthSession,
  saveAuthResponse,
  subscribeToAuthSession,
  type AuthSession,
} from './authSession';

interface AuthContextValue {
  session: AuthSession | null;
  user: UserSummary | null;
  isAuthenticated: boolean;
  signin: (payload: SigninRequest) => Promise<void>;
  signup: (payload: SignupRequest) => Promise<UserSummary>;
  signout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  // 세션 상태의 단일 갱신 경로: authSession의 저장/삭제가 보내는 AUTH_EVENT 구독.
  // signin/signout은 저장소만 바꾸고 setSession을 직접 호출하지 않는다(이중 갱신 제거, FE-6).
  const [session, setSession] = useState<AuthSession | null>(() => readAuthSession());

  useEffect(() => subscribeToAuthSession(() => setSession(readAuthSession())), []);

  useEffect(() => {
    if (!session) return;
    const remainingMs = session.expiresAt - Date.now();
    const maximumTimerMs = 2_147_483_647;
    // 만료 시각에 readAuthSession이 저장소를 정리(EXPIRED 사유 기록)하고 null을 돌려준다.
    const timer = window.setTimeout(
      () => setSession(readAuthSession()),
      Math.min(Math.max(remainingMs + 1, 0), maximumTimerMs),
    );
    return () => window.clearTimeout(timer);
  }, [session]);

  const signin = useCallback(async (payload: SigninRequest): Promise<void> => {
    const response = await authApi.signin(payload);
    saveAuthResponse(response);
  }, []);

  const signup = useCallback((payload: SignupRequest) => authApi.signup(payload), []);

  const signout = useCallback(() => {
    clearAuthSession('USER');
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      user: session?.user ?? null,
      isAuthenticated: session !== null,
      signin,
      signup,
      signout,
    }),
    [session, signin, signup, signout],
  );

  return <AuthContext value={value}>{children}</AuthContext>;
}

export const useAuth = (): AuthContextValue => {
  const context = use(AuthContext);
  if (!context) throw new Error('useAuth는 AuthProvider 안에서 사용해야 합니다.');
  return context;
};
