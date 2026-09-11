import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { deviceApi } from '../api/services';
import type { DeviceResponse } from '../api/types';
import { AuthProvider } from '../features/auth/AuthContext';
import { AppShell } from './AppShell';

const device = (
  footSide: DeviceResponse['footSide'],
  status: DeviceResponse['status'],
): DeviceResponse => ({
  deviceId:
    footSide === 'LEFT'
      ? 'b4b96290-ad73-42d9-ae21-1446f1258861'
      : '64eb539f-4b48-44f6-bb30-d26861463ca6',
  serialNumber: `SMART-INSOLE-${footSide === 'LEFT' ? 'L' : 'R'}-12345678`,
  displayName: footSide === 'LEFT' ? '왼발 인솔' : '오른발 인솔',
  footSide,
  sensorCount: 8,
  sensorLayoutVersion: 'layout-s01s08-v1',
  firmwareVersion: '0.2.0',
  adcMax: 4095,
  activeCalibrationVersion: 'identity-v1',
  status,
  lastSeenAt: '2026-09-04T01:00:00Z',
  lastBatteryPercent: footSide === 'LEFT' ? 87 : 79,
  lastBatteryMv: 3900,
  registeredAt: '2026-09-02T07:00:00Z',
});

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

const renderShell = (initialPath: string) => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route element={<AppShell />}>
              <Route element={<p>홈 본문</p>} path="/dashboard" />
              <Route element={<p>결과 본문</p>} path="/measurements/:sessionId/result" />
              <Route element={<p>설정 본문</p>} path="/settings" />
              <Route element={<p>로그인 본문</p>} path="/login" />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
};

describe('AppShell', () => {
  beforeEach(() => {
    seedSession();
    vi.spyOn(deviceApi, 'list').mockResolvedValue([
      device('LEFT', 'ACTIVE'),
      device('RIGHT', 'DISCONNECTED'),
    ]);
  });

  it('사이드바 메뉴·상단바 제목·연결 상태·배터리를 표시하고 결과 상세를 결과 분석 메뉴에 연결한다', async () => {
    renderShell('/measurements/5803f871-9fca-4a7f-a2c7-9b567a92a6cf/result');

    const nav = screen.getByRole('navigation', { name: '주요 메뉴' });
    expect(
      within(nav)
        .getAllByRole('link')
        .map((link) => link.getAttribute('aria-label')),
    ).toEqual([
      '홈',
      '실시간 추적',
      '결과 분석',
      '운동 가이드',
      '기록',
      '내 인솔',
      '설정',
      '도움말',
    ]);
    expect(within(nav).getByRole('link', { name: '결과 분석' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '실시간 추적' })).not.toHaveAttribute(
      'aria-current',
    );
    expect(screen.getByText('결과 분석', { selector: '.topbar__title' })).toBeInTheDocument();
    expect(document.title).toBe('결과 분석 · 바른걸음');

    // 연결 상태는 색이 아닌 텍스트로도 전달된다(한쪽 끊김 → '연결 끊김').
    expect(await screen.findByText('연결 끊김')).toBeInTheDocument();
    expect(screen.getByText('왼발 사용 가능')).toBeInTheDocument();
    expect(screen.getByText('오른발 연결 끊김')).toBeInTheDocument();
    expect(screen.getByText('87%')).toBeInTheDocument();
    expect(screen.getByText('79%')).toBeInTheDocument();
    expect(screen.queryByText(/bluetooth/i)).toBeNull();

    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
    expect(screen.getByText(/의료 진단을 제공하지 않습니다/)).toBeInTheDocument();
  });

  it('사용자 메뉴는 설정·도움말·로그아웃을 제공하고 Escape로 닫힌다', async () => {
    const user = userEvent.setup();
    renderShell('/dashboard');

    const trigger = screen.getByRole('button', { name: /홍길동님/ });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    const menu = screen.getByRole('menu', { name: '사용자 메뉴' });
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['설정', '도움말', '로그아웃']);

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).toBeNull();
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await user.click(screen.getByRole('menuitem', { name: '로그아웃' }));
    expect(await screen.findByText('로그인 본문')).toBeInTheDocument();
    expect(window.sessionStorage.getItem('smart-insole.auth.v1')).toBeNull();
  });

  it('모바일 메뉴 버튼은 드로어를 열고 경로가 바뀌면 닫는다', async () => {
    const user = userEvent.setup();
    renderShell('/dashboard');

    const toggle = screen.getByRole('button', { name: '메뉴 열기' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(screen.getByRole('button', { name: '메뉴 닫기' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(document.body.style.overflow).toBe('hidden');
    expect(screen.getByRole('link', { name: '홈' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toHaveFocus();
    expect(document.body.style.overflow).toBe('');

    await user.click(screen.getByRole('button', { name: '메뉴 열기' }));
    await user.click(screen.getByRole('link', { name: '설정' }));
    expect(await screen.findByText('설정 본문')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });
});
