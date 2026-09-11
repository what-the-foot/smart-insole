import type { IconName } from '../components/Icon';

// 사이드바 메뉴 정의. 컴포넌트가 아닌 모듈에 두어 react-refresh 규칙(only-export-components)을 지킨다.
export interface NavItem {
  to: string;
  label: string;
  icon: IconName;
}

export const navItems: readonly NavItem[] = [
  { to: '/dashboard', label: '홈', icon: 'home' },
  { to: '/measurements/live', label: '실시간 추적', icon: 'activity' },
  { to: '/results', label: '결과 분석', icon: 'chart' },
  { to: '/recommendations', label: '운동 가이드', icon: 'guide' },
  { to: '/history', label: '기록', icon: 'history' },
  { to: '/devices', label: '내 인솔', icon: 'device' },
];

export const bottomNavItems: readonly NavItem[] = [
  { to: '/settings', label: '설정', icon: 'settings' },
  { to: '/help', label: '도움말', icon: 'help' },
];

const normalize = (pathname: string): string => {
  const trimmed = pathname.replace(/\/+$/, '');
  return trimmed === '' ? '/' : trimmed;
};

const RESULT_DETAIL = /^\/measurements\/[^/]+\/result$/;
const LIVE_DETAIL = /^\/measurements\/[^/]+\/live$/;

const startsWithSegment = (pathname: string, prefix: string): boolean =>
  pathname === prefix || pathname.startsWith(`${prefix}/`);

// 메뉴 항목의 활성 여부. 상세 경로가 어떤 메뉴에 속하는지 여기서만 결정한다.
export const isActiveFor = (to: string, pathname: string): boolean => {
  const path = normalize(pathname);
  switch (to) {
    case '/measurements/live':
      return startsWithSegment(path, '/measurements') && !RESULT_DETAIL.test(path);
    case '/results':
      return path === '/results' || RESULT_DETAIL.test(path);
    case '/recommendations':
      return startsWithSegment(path, '/recommendations');
    default:
      return startsWithSegment(path, to);
  }
};

export const BRAND_NAME = '바른걸음';
export const BRAND_TAGLINE = 'Smart Insole for Better Steps';

// 상단바 제목. 페이지 본문의 h1과 별개로 현재 위치를 짧게 알려준다.
export const titleFor = (pathname: string): string => {
  const path = normalize(pathname);
  if (path === '/dashboard') return '대시보드';
  if (path === '/measurements/new') return '새 측정';
  if (path === '/measurements/live' || LIVE_DETAIL.test(path)) return '실시간 측정';
  if (path === '/results' || RESULT_DETAIL.test(path)) return '결과 분석';
  if (startsWithSegment(path, '/recommendations')) return '운동 가이드';
  if (startsWithSegment(path, '/history')) return '측정 기록';
  if (startsWithSegment(path, '/devices')) return '내 인솔';
  if (startsWithSegment(path, '/settings')) return '설정';
  if (startsWithSegment(path, '/help')) return '도움말';
  return BRAND_NAME;
};
