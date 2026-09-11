import { bottomNavItems, isActiveFor, navItems, titleFor } from './navigation';

describe('navigation', () => {
  it('사이드바 메뉴 순서와 경로를 고정한다', () => {
    expect(navItems.map((item) => [item.label, item.to])).toEqual([
      ['홈', '/dashboard'],
      ['실시간 추적', '/measurements/live'],
      ['결과 분석', '/results'],
      ['운동 가이드', '/recommendations'],
      ['기록', '/history'],
      ['내 인솔', '/devices'],
    ]);
    expect(bottomNavItems.map((item) => [item.label, item.to])).toEqual([
      ['설정', '/settings'],
      ['도움말', '/help'],
    ]);
  });

  it('titleFor는 경로별 상단바 제목을 돌려준다', () => {
    expect(titleFor('/dashboard')).toBe('대시보드');
    expect(titleFor('/measurements/live')).toBe('실시간 측정');
    expect(titleFor('/measurements/5803f871-9fca-4a7f-a2c7-9b567a92a6cf/live')).toBe('실시간 측정');
    expect(titleFor('/measurements/new')).toBe('새 측정');
    expect(titleFor('/results')).toBe('결과 분석');
    expect(titleFor('/measurements/5803f871-9fca-4a7f-a2c7-9b567a92a6cf/result')).toBe('결과 분석');
    expect(titleFor('/recommendations')).toBe('운동 가이드');
    expect(titleFor('/recommendations/ANKLE_STABILITY_BASIC')).toBe('운동 가이드');
    expect(titleFor('/history')).toBe('측정 기록');
    expect(titleFor('/devices')).toBe('내 인솔');
    expect(titleFor('/settings')).toBe('설정');
    expect(titleFor('/help/')).toBe('도움말');
    expect(titleFor('/unknown')).toBe('바른걸음');
  });

  it('isActiveFor는 상세 경로를 상위 메뉴에 연결한다', () => {
    const resultPath = '/measurements/5803f871-9fca-4a7f-a2c7-9b567a92a6cf/result';
    const livePath = '/measurements/5803f871-9fca-4a7f-a2c7-9b567a92a6cf/live';

    expect(isActiveFor('/measurements/live', '/measurements/new')).toBe(true);
    expect(isActiveFor('/measurements/live', livePath)).toBe(true);
    expect(isActiveFor('/measurements/live', resultPath)).toBe(false);

    expect(isActiveFor('/results', '/results')).toBe(true);
    expect(isActiveFor('/results', resultPath)).toBe(true);
    expect(isActiveFor('/results', livePath)).toBe(false);

    expect(isActiveFor('/recommendations', '/recommendations/ANKLE_STABILITY_BASIC')).toBe(true);
    expect(isActiveFor('/dashboard', '/dashboard')).toBe(true);
    expect(isActiveFor('/dashboard', '/devices')).toBe(false);
    // 접두어가 겹치는 다른 경로는 활성화하지 않는다.
    expect(isActiveFor('/help', '/helpdesk')).toBe(false);
  });

  it('활성 메뉴는 경로마다 하나만 존재한다', () => {
    const all = [...navItems, ...bottomNavItems];
    for (const pathname of [
      '/dashboard',
      '/measurements/new',
      '/measurements/abc/live',
      '/measurements/abc/result',
      '/results',
      '/recommendations',
      '/history',
      '/devices',
      '/settings',
      '/help',
    ]) {
      expect(all.filter((item) => isActiveFor(item.to, pathname))).toHaveLength(1);
    }
  });
});
