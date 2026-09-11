import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { PREFERENCES_STORAGE_KEY, readPreferences } from '../app/preferences';
import { AuthProvider } from '../features/auth/AuthContext';
import { SettingsPage } from './SettingsPage';

const seedSession = () => {
  window.sessionStorage.setItem(
    'smart-insole.auth.v1',
    JSON.stringify({
      accessToken: 'token',
      expiresAt: Date.now() + 3_600_000,
      user: {
        userId: 'a7d3e6b8-6a37-4a5e-9a0f-0a6b4c2e1d11',
        email: 'walker@example.com',
        name: '홍길동',
        createdAt: '2026-08-01T00:00:00Z',
      },
    }),
  );
};

const renderSettings = () =>
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/settings']}>
        <SettingsPage />
      </MemoryRouter>
    </AuthProvider>,
  );

describe('SettingsPage', () => {
  beforeEach(() => {
    window.localStorage.removeItem(PREFERENCES_STORAGE_KEY);
    seedSession();
  });

  it('계정 정보를 읽기 전용으로 보여준다', () => {
    renderSettings();
    expect(screen.getByText('홍길동')).toBeInTheDocument();
    expect(screen.getByText('walker@example.com')).toBeInTheDocument();
    expect(screen.queryByLabelText('이메일')).toBeNull();
  });

  it('화면 설정을 localStorage에 저장한다', async () => {
    const user = userEvent.setup();
    renderSettings();

    expect(screen.getByRole('radio', { name: /^50Hz/ })).toBeChecked();
    await user.click(screen.getByRole('radio', { name: /^100Hz/ }));
    await user.click(screen.getByRole('radio', { name: /^센서 점/ }));
    await user.click(screen.getByRole('checkbox', { name: /애니메이션 줄이기/ }));

    expect(readPreferences()).toEqual({
      defaultSampleRateHz: 100,
      heatmapMode: 'points',
      reduceMotion: true,
    });
    expect(document.documentElement.dataset.reduceMotion).toBe('true');
    expect(screen.getByRole('radio', { name: /^100Hz/ })).toBeChecked();
  });

  it('다시 로그인 버튼이 재로그인 다이얼로그를 연다', async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(screen.getByRole('button', { name: '다시 로그인' }));
    expect(screen.getByRole('dialog', { name: '로그인 세션이 곧 만료됩니다' })).toBeInTheDocument();
    expect(screen.getByLabelText('이메일')).toHaveValue('walker@example.com');
  });
});
