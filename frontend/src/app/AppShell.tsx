import { useEffect, useId, useRef, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useDevices } from '../api/queries';
import type { DeviceResponse, DeviceStatus, FootSide } from '../api/types';
import { Icon } from '../components/Icon';
import { SessionExpiryBanner } from '../components/SessionExpiryBanner';
import { useAuth } from '../features/auth/AuthContext';
import { batteryLabel, deviceStatusLabels } from '../utils/labels';
import {
  BRAND_NAME,
  BRAND_TAGLINE,
  bottomNavItems,
  isActiveFor,
  navItems,
  titleFor,
  type NavItem,
} from './navigation';
import { applyMotionPreference, readPreferences } from './preferences';

const DESKTOP_QUERY = '(min-width: 900px)';

export function AppShell() {
  const { user, signout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const sidebarId = useId();
  const title = titleFor(location.pathname);

  useEffect(() => {
    document.title = `${title} · ${BRAND_NAME}`;
  }, [title]);

  useEffect(() => {
    applyMotionPreference(readPreferences());
  }, []);

  // 경로가 바뀌면 모바일 드로어를 닫는다.
  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  // 드로어가 열린 상태: 스크롤 잠금, Escape로 닫기, 포커스 이동·복귀, 데스크톱 폭으로 커지면 닫기.
  useEffect(() => {
    if (!menuOpen) return;
    const menuButton = menuButtonRef.current;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    sidebarRef.current?.querySelector<HTMLElement>('.sidebar__nav a')?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    const media = window.matchMedia(DESKTOP_QUERY);
    const onMediaChange = (event: MediaQueryListEvent) => {
      if (event.matches) setMenuOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    media.addEventListener('change', onMediaChange);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', onKeyDown);
      media.removeEventListener('change', onMediaChange);
      menuButton?.focus();
    };
  }, [menuOpen]);

  const handleSignout = () => {
    signout();
    void navigate('/login', { replace: true });
  };

  return (
    <div className="app-frame">
      <a className="skip-link" href="#main-content">
        본문으로 바로가기
      </a>
      <aside
        className={menuOpen ? 'sidebar sidebar--open' : 'sidebar'}
        id={sidebarId}
        ref={sidebarRef}
      >
        <Link className="brand sidebar__brand" to="/dashboard">
          <span className="brand__mark">
            <Icon name="foot" />
          </span>
          <span className="brand__text">
            <strong>{BRAND_NAME}</strong>
            <small>{BRAND_TAGLINE}</small>
          </span>
        </Link>
        <nav aria-label="주요 메뉴" className="sidebar__nav">
          <ul className="sidebar__list">
            {navItems.map((item) => (
              <li key={item.to}>
                <SidebarLink item={item} pathname={location.pathname} />
              </li>
            ))}
          </ul>
          <ul className="sidebar__list sidebar__list--bottom">
            {bottomNavItems.map((item) => (
              <li key={item.to}>
                <SidebarLink item={item} pathname={location.pathname} />
              </li>
            ))}
          </ul>
        </nav>
      </aside>
      {menuOpen ? (
        <div aria-hidden="true" className="sidebar-overlay" onClick={() => setMenuOpen(false)} />
      ) : null}
      <div className="app-body">
        <header className="topbar">
          <button
            aria-controls={sidebarId}
            aria-expanded={menuOpen}
            aria-label={menuOpen ? '메뉴 닫기' : '메뉴 열기'}
            className="icon-button topbar__menu-button"
            onClick={() => setMenuOpen((value) => !value)}
            ref={menuButtonRef}
            type="button"
          >
            <Icon name={menuOpen ? 'x' : 'menu'} />
          </button>
          <p className="topbar__title">{title}</p>
          <InsoleStatus />
          <UserMenu name={user?.name ?? '사용자'} onSignout={handleSignout} />
        </header>
        {/* 드로어가 열린 동안 오버레이 아래 영역은 inert로 두어 키보드 포커스가 가려진 컨트롤로 가지 않게 한다.
            상단바는 토글 버튼이 있으므로 그대로 둔다. */}
        <SessionExpiryBanner inert={menuOpen} />
        <main className="main-content" id="main-content" inert={menuOpen} tabIndex={-1}>
          <Outlet />
        </main>
        <footer className="app-footer" inert={menuOpen}>
          <p>
            © 2026 바른걸음 · 본 서비스는 의료 진단을 제공하지 않습니다. 통증이 지속되면 전문가
            상담을 권장합니다.
          </p>
        </footer>
      </div>
    </div>
  );
}

function SidebarLink({ item, pathname }: { item: NavItem; pathname: string }) {
  const active = isActiveFor(item.to, pathname);
  return (
    <Link
      aria-current={active ? 'page' : undefined}
      aria-label={item.label}
      className={active ? 'nav-link nav-link--active' : 'nav-link'}
      title={item.label}
      to={item.to}
    >
      <Icon name={item.icon} />
      <span className="nav-link__label">{item.label}</span>
    </Link>
  );
}

const sideText: Record<FootSide, string> = { LEFT: '왼발', RIGHT: '오른발' };

type DotTone = 'on' | 'off' | 'warn' | 'none';

const toneFor = (status: DeviceStatus | undefined): DotTone => {
  if (status === 'ACTIVE') return 'on';
  if (status === 'DISCONNECTED') return 'off';
  if (status === undefined) return 'none';
  return 'warn';
};

// 한쪽 발에 여러 인솔이 있으면 ACTIVE를 우선 보여준다(측정에 사용할 가능성이 큰 기기).
const pickDevice = (
  devices: readonly DeviceResponse[] | undefined,
  side: FootSide,
): DeviceResponse | undefined => {
  const candidates = devices?.filter((device) => device.footSide === side) ?? [];
  return candidates.find((device) => device.status === 'ACTIVE') ?? candidates[0];
};

const summarizeStatus = (
  left: DeviceResponse | undefined,
  right: DeviceResponse | undefined,
): string => {
  const statuses = [left?.status, right?.status].filter(
    (status): status is DeviceStatus => status !== undefined,
  );
  if (statuses.length === 0) return '등록된 인솔 없음';
  if (statuses.includes('DISCONNECTED')) return deviceStatusLabels.DISCONNECTED;
  if (statuses.includes('CALIBRATION_REQUIRED')) return deviceStatusLabels.CALIBRATION_REQUIRED;
  if (statuses.includes('INACTIVE')) return deviceStatusLabels.INACTIVE;
  if (statuses.length < 2) return '한쪽 인솔만 등록됨';
  return deviceStatusLabels.ACTIVE;
};

// 앱은 BLE를 직접 다루지 않고 수신기 heartbeat로 상태를 받으므로 'Bluetooth'가 아니라 '인솔 연결'로 부른다.
function InsoleStatus() {
  const devices = useDevices();
  const left = pickDevice(devices.data, 'LEFT');
  const right = pickDevice(devices.data, 'RIGHT');
  const summary = devices.isPending
    ? '확인 중'
    : devices.isError
      ? '확인 불가'
      : summarizeStatus(left, right);
  const detail = [left, right]
    .filter((device): device is DeviceResponse => device !== undefined)
    .map((device) => `${sideText[device.footSide]}: ${deviceStatusLabels[device.status]}`)
    .join(' · ');

  return (
    <div className="topbar__status">
      <span className="status-chip status-chip--connection" title={detail || summary}>
        <Icon name="link" />
        <span className="status-chip__label">인솔 연결</span>
        <SideDot device={left} side="LEFT" />
        <SideDot device={right} side="RIGHT" />
        <span className="status-chip__value">{summary}</span>
      </span>
      {left ? <BatteryChip device={left} /> : null}
      {right ? <BatteryChip device={right} /> : null}
    </div>
  );
}

function SideDot({ device, side }: { device: DeviceResponse | undefined; side: FootSide }) {
  const tone = toneFor(device?.status);
  const label = device ? deviceStatusLabels[device.status] : '미등록';
  return (
    <span className={`conn-dot conn-dot--${tone}`}>
      <span aria-hidden="true">{side === 'LEFT' ? 'L' : 'R'}</span>
      <span className="sr-only">
        {sideText[side]} {label}
      </span>
    </span>
  );
}

function BatteryChip({ device }: { device: DeviceResponse }) {
  // 칩에는 퍼센트(또는 '미보정')만, 툴팁에는 mV까지 포함한 전체 배터리 문구를 보여준다.
  const short = batteryLabel({ ...device, lastBatteryMv: null });
  return (
    <span
      className={`status-chip status-chip--${device.footSide.toLowerCase()}`}
      title={`${sideText[device.footSide]} 배터리 ${batteryLabel(device)}`}
    >
      <Icon name="battery" />
      <span aria-hidden="true">{device.footSide === 'LEFT' ? 'L' : 'R'}</span>
      <span className="sr-only">{sideText[device.footSide]} 배터리</span>
      <span className="status-chip__value">{short}</span>
    </span>
  );
}

function UserMenu({ name, onSignout }: { name: string; onSignout: () => void }) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const location = useLocation();

  useEffect(() => {
    setOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!open) return;
    menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const onPointerDown = (event: MouseEvent) => {
      if (event.target instanceof Node && !rootRef.current?.contains(event.target)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const moveFocus = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const items = Array.from(
      menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [],
    );
    if (items.length === 0) return;
    event.preventDefault();
    const index = items.findIndex((item) => item === document.activeElement);
    const step = event.key === 'ArrowDown' ? 1 : -1;
    items[(index + step + items.length) % items.length]?.focus();
  };

  return (
    <div className="user-menu" ref={rootRef}>
      <button
        aria-controls={menuId}
        aria-expanded={open}
        aria-haspopup="menu"
        className="user-menu__trigger"
        onClick={() => setOpen((value) => !value)}
        ref={triggerRef}
        type="button"
      >
        <span aria-hidden="true" className="user-menu__avatar">
          {name.trim().charAt(0) || '?'}
        </span>
        <span className="user-menu__name">{name}님</span>
        <Icon name="chevron-down" />
      </button>
      {open ? (
        <div
          aria-label="사용자 메뉴"
          className="user-menu__dropdown"
          id={menuId}
          onKeyDown={moveFocus}
          ref={menuRef}
          role="menu"
        >
          <Link className="user-menu__item" role="menuitem" to="/settings">
            <Icon name="settings" />
            설정
          </Link>
          <Link className="user-menu__item" role="menuitem" to="/help">
            <Icon name="help" />
            도움말
          </Link>
          <button className="user-menu__item" onClick={onSignout} role="menuitem" type="button">
            <Icon name="logout" />
            로그아웃
          </button>
        </div>
      ) : null}
    </div>
  );
}
