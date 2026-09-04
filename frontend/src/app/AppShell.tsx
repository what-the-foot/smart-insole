import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Icon } from '../components/Icon';
import { SessionExpiryBanner } from '../components/SessionExpiryBanner';
import { useAuth } from '../features/auth/AuthContext';

const navItems = [
  { to: '/dashboard', label: '대시보드', icon: 'home' as const },
  { to: '/devices', label: '내 인솔', icon: 'device' as const },
  { to: '/history', label: '측정 기록', icon: 'history' as const },
];

export function AppShell() {
  const { user, signout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);

  const handleSignout = () => {
    signout();
    void navigate('/login', { replace: true });
  };

  return (
    <div className="app-frame">
      <a className="skip-link" href="#main-content">
        본문으로 바로가기
      </a>
      <header className="topbar">
        <NavLink className="brand" to="/dashboard">
          <span className="brand__mark">
            <Icon name="activity" />
          </span>
          <span>바른걸음</span>
        </NavLink>
        <nav aria-label="주요 메뉴" className={menuOpen ? 'main-nav main-nav--open' : 'main-nav'}>
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              className={({ isActive }) => `nav-link${isActive ? ' nav-link--active' : ''}`}
              onClick={() => setMenuOpen(false)}
              to={item.to}
            >
              <Icon name={item.icon} />
              <span>{item.label}</span>
            </NavLink>
          ))}
          <button className="nav-link mobile-signout" onClick={handleSignout} type="button">
            <Icon name="logout" />
            <span>로그아웃</span>
          </button>
        </nav>
        <div className="user-menu">
          <span className="user-chip">
            <Icon name="user" />
            <span>
              <small>안녕하세요</small>
              {user?.name}
            </span>
          </span>
          <button
            aria-label="로그아웃"
            className="icon-button icon-button--quiet"
            onClick={handleSignout}
            title="로그아웃"
          >
            <Icon name="logout" />
          </button>
          <button
            aria-expanded={menuOpen}
            aria-label={menuOpen ? '메뉴 닫기' : '메뉴 열기'}
            className="icon-button mobile-menu-button"
            onClick={() => setMenuOpen((value) => !value)}
          >
            <Icon name={menuOpen ? 'x' : 'menu'} />
          </button>
        </div>
      </header>
      <SessionExpiryBanner />
      <main className="main-content" id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="app-footer">
        <p>바른걸음은 관찰된 족압 패턴을 이해하도록 돕습니다. 의료 진단을 제공하지 않습니다.</p>
      </footer>
    </div>
  );
}
