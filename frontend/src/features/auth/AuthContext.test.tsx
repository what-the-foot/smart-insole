import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { authApi } from '../../api/services';
import { SigninPage } from '../../pages/SigninPage';
import { AuthProvider, useAuth } from './AuthContext';
import { ProtectedRoute } from './ProtectedRoute';

describe('인증 흐름', () => {
  it('로그인 성공 시 인증 세션을 저장하고 요청했던 보호 화면으로 이동한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(authApi, 'signin').mockResolvedValue({
      tokenType: 'Bearer',
      accessToken: 'test-access-token',
      expiresInSeconds: 3600,
      user: {
        userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
        email: 'walker@example.com',
        name: '테스트 사용자',
        createdAt: '2026-09-02T07:00:00Z',
      },
    });

    render(
      <MemoryRouter initialEntries={[{ pathname: '/login', state: { from: '/dashboard' } }]}>
        <AuthProvider>
          <Routes>
            <Route element={<SigninPage />} path="/login" />
            <Route element={<h1>대시보드 도착</h1>} path="/dashboard" />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText('이메일'), 'walker@example.com');
    await user.type(screen.getByLabelText('비밀번호'), 'safe-password');
    await user.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByRole('heading', { name: '대시보드 도착' })).toBeInTheDocument();
    expect(window.sessionStorage.getItem('smart-insole.auth.v1')).not.toContain('safe-password');
  });

  it('미인증 사용자를 로그인 화면으로 돌려보낸다', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider>
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route element={<h1>보호된 화면</h1>} path="/dashboard" />
            </Route>
            <Route element={<h1>로그인이 필요합니다</h1>} path="/login" />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '로그인이 필요합니다' })).toBeInTheDocument();
    expect(screen.queryByText('보호된 화면')).not.toBeInTheDocument();
  });

  it('열린 화면에서도 access token 만료 시 인증 상태와 저장소를 정리한다', async () => {
    const now = Date.now();
    window.sessionStorage.setItem('smart-insole.auth.v1', JSON.stringify({
      accessToken: 'expiring-token',
      expiresAt: now + 30,
      user: {
        userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
        email: 'walker@example.com',
        name: '테스트 사용자',
        createdAt: '2026-09-02T07:00:00Z',
      },
    }));

    function AuthState() {
      const { isAuthenticated } = useAuth();
      return <p>{isAuthenticated ? '인증됨' : '만료됨'}</p>;
    }

    render(<AuthProvider><AuthState /></AuthProvider>);
    expect(screen.getByText('인증됨')).toBeInTheDocument();
    await act(async () => {
      await new Promise((resolve) => window.setTimeout(resolve, 60));
    });
    expect(screen.getByText('만료됨')).toBeInTheDocument();
    expect(window.sessionStorage.getItem('smart-insole.auth.v1')).toBeNull();
    expect(window.sessionStorage.getItem('smart-insole.auth.signout-reason.v1')).toBe('EXPIRED');
  });

  it('만료로 로그아웃된 뒤 로그인 화면에 사유를 한 번만 안내한다', () => {
    window.sessionStorage.setItem('smart-insole.auth.v1', JSON.stringify({
      accessToken: 'expired-token',
      expiresAt: Date.now() - 1,
      user: {
        userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
        email: 'walker@example.com',
        name: '테스트 사용자',
        createdAt: '2026-09-02T07:00:00Z',
      },
    }));

    const first = render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <Routes>
            <Route element={<SigninPage />} path="/login" />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(screen.getByRole('status')).toHaveTextContent('로그인 세션이 만료되어 로그아웃되었습니다.');
    first.unmount();

    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <Routes>
            <Route element={<SigninPage />} path="/login" />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('사용자가 직접 로그아웃하면 사유 안내 없이 인증 상태만 정리한다', async () => {
    const user = userEvent.setup();
    window.sessionStorage.setItem('smart-insole.auth.v1', JSON.stringify({
      accessToken: 'valid-token',
      expiresAt: Date.now() + 60_000,
      user: {
        userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
        email: 'walker@example.com',
        name: '테스트 사용자',
        createdAt: '2026-09-02T07:00:00Z',
      },
    }));

    function SignoutButton() {
      const { isAuthenticated, signout } = useAuth();
      return <button onClick={signout} type="button">{isAuthenticated ? '로그아웃' : '로그아웃됨'}</button>;
    }

    render(<AuthProvider><SignoutButton /></AuthProvider>);
    await user.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(screen.getByRole('button', { name: '로그아웃됨' })).toBeInTheDocument();
    expect(window.sessionStorage.getItem('smart-insole.auth.v1')).toBeNull();
    expect(window.sessionStorage.getItem('smart-insole.auth.signout-reason.v1')).toBe('USER');
  });
});
