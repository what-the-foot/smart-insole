import { act, fireEvent, render, renderHook, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { authApi } from '../../api/services';
import type { UserSummary } from '../../api/types';
import { SessionExpiryBanner } from '../../components/SessionExpiryBanner';
import { AuthProvider } from './AuthContext';
import { saveAuthResponse } from './authSession';
import { formatRemaining, SESSION_EXPIRY_WARNING_MS, useSessionExpiry } from './useSessionExpiry';

const user: UserSummary = {
  userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
  email: 'walker@example.com',
  name: '테스트 사용자',
  createdAt: '2026-09-02T07:00:00Z',
};

const signin = (expiresInSeconds: number, accessToken = 'test-token') =>
  saveAuthResponse({ tokenType: 'Bearer', accessToken, expiresInSeconds, user });

const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

describe('useSessionExpiry (fake timers)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-04T01:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('만료 5분 전부터 경고하고 경고 구간에서는 1초 단위로 남은 시간을 갱신한다', () => {
    signin(6 * 60);
    const { result } = renderHook(() => useSessionExpiry(), { wrapper });

    expect(SESSION_EXPIRY_WARNING_MS).toBe(300_000);
    expect(result.current.warning).toBe(false);
    expect(result.current.remainingMs).toBe(360_000);

    act(() => {
      vi.advanceTimersByTime(59_000);
    });
    expect(result.current.warning).toBe(false);

    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(result.current.warning).toBe(true);
    expect(result.current.remainingMs).toBe(300_000);

    act(() => {
      vi.advanceTimersByTime(90_000);
    });
    expect(result.current.remainingMs).toBe(210_000);
    expect(formatRemaining(result.current.remainingMs ?? 0)).toBe('3분 30초');
    expect(result.current.expired).toBe(false);
  });

  it('세션이 없으면 경고·만료 상태가 아니다', () => {
    const { result } = renderHook(() => useSessionExpiry(), { wrapper });
    expect(result.current).toEqual({
      expiresAt: null,
      remainingMs: null,
      warning: false,
      expired: false,
    });
  });

  it.each([
    [300_000, '5분'],
    [210_000, '3분 30초'],
    [45_000, '45초'],
    [500, '1초'],
  ])('%s ms를 %s로 표시한다', (remainingMs, expected) => {
    expect(formatRemaining(remainingMs)).toBe(expected);
  });
});

describe('SessionExpiryBanner (fake timers)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-04T01:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('5분 전에는 숨기고 경고 구간에서 배너와 재로그인 다이얼로그를 제공한다', () => {
    signin(6 * 60);
    render(
      <AuthProvider>
        <SessionExpiryBanner />
      </AuthProvider>,
    );

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByRole('status')).toHaveTextContent('로그인 세션이 5분 후 만료됩니다.');

    fireEvent.click(screen.getByRole('button', { name: '다시 로그인' }));
    expect(screen.getByRole('dialog', { name: '로그인 세션이 곧 만료됩니다' })).toBeInTheDocument();
    expect(screen.getByLabelText('이메일')).toHaveValue('walker@example.com');
    expect(screen.getByLabelText('이메일')).toHaveAttribute('readonly');

    fireEvent.click(screen.getByRole('button', { name: '나중에' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('재로그인에 성공하면 토큰이 갱신되어 다이얼로그와 배너가 사라진다', async () => {
    signin(4 * 60, 'old-token');
    const signinApi = vi.spyOn(authApi, 'signin').mockResolvedValue({
      tokenType: 'Bearer',
      accessToken: 'fresh-token',
      expiresInSeconds: 3600,
      user,
    });
    render(
      <AuthProvider>
        <SessionExpiryBanner />
      </AuthProvider>,
    );
    expect(screen.getByRole('status')).toHaveTextContent('4분 후 만료');

    fireEvent.click(screen.getByRole('button', { name: '다시 로그인' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('비밀번호'), {
      target: { value: 'safe-password' },
    });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: '다시 로그인' }));
      await Promise.resolve();
    });

    expect(signinApi).toHaveBeenCalledWith({
      email: 'walker@example.com',
      password: 'safe-password',
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(window.sessionStorage.getItem('smart-insole.auth.v1')).toContain('fresh-token');
    expect(window.sessionStorage.getItem('smart-insole.auth.v1')).not.toContain('safe-password');
  });
});
