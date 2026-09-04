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
  const [session, setSession] = useState<AuthSession | null>(() => readAuthSession());

  useEffect(() => subscribeToAuthSession(() => setSession(readAuthSession())), []);

  useEffect(() => {
    if (!session) return;
    const remainingMs = session.expiresAt - Date.now();
    const maximumTimerMs = 2_147_483_647;
    const timer = window.setTimeout(
      () => setSession(readAuthSession()),
      Math.min(Math.max(remainingMs + 1, 0), maximumTimerMs),
    );
    return () => window.clearTimeout(timer);
  }, [session]);

  const signin = useCallback(async (payload: SigninRequest): Promise<void> => {
    const response = await authApi.signin(payload);
    setSession(saveAuthResponse(response));
  }, []);

  const signup = useCallback((payload: SignupRequest) => authApi.signup(payload), []);

  const signout = useCallback(() => {
    clearAuthSession();
    setSession(null);
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
