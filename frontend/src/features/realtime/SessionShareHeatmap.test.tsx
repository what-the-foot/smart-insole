import { render, screen } from '@testing-library/react';
import type { SensorLayoutResponse } from '../../api/types';
import { PressureLegend, SessionShareHeatmap } from './FootPressureHeatmap';
import { pressureColor } from './heatmapColors';
import { LEGACY_SHARE_MESSAGE, sensorShareMax, topSensors } from './sensorShare';

const labeledLayout: SensorLayoutResponse = {
  version: 'layout-s01s08-v1',
  sensorCount: 8,
  points: [
    { label: 'S01', index: 0, x: 0.4, y: 0.88, region: 'HEEL', medialLateral: 'MEDIAL' },
    { label: 'S02', index: 1, x: 0.62, y: 0.88, region: 'HEEL', medialLateral: 'LATERAL' },
    { label: 'S03', index: 2, x: 0.36, y: 0.62, region: 'MIDFOOT', medialLateral: 'MEDIAL' },
    { label: 'S04', index: 3, x: 0.66, y: 0.62, region: 'MIDFOOT', medialLateral: 'LATERAL' },
    { label: 'S05', index: 4, x: 0.32, y: 0.36, region: 'FOREFOOT', medialLateral: 'MEDIAL' },
    { label: 'S06', index: 5, x: 0.5, y: 0.34, region: 'FOREFOOT', medialLateral: 'CENTER' },
    { label: 'S07', index: 6, x: 0.7, y: 0.38, region: 'FOREFOOT', medialLateral: 'LATERAL' },
    { label: 'S08', index: 7, x: 0.36, y: 0.12, region: 'TOE', medialLateral: 'MEDIAL' },
  ],
};

const leftShare = [18, 12, 8, 7, 20, 15, 10, 10];
const rightShare = [30, 20, 5, 5, 15, 10, 10, 5];

describe('SessionShareHeatmap (mode=share)', () => {
  it('센서별 비율을 정수 %로 원 안에 표시하고 S01..S08 라벨과 접근 가능한 이름을 제공한다', () => {
    const { container } = render(
      <SessionShareHeatmap
        layout={labeledLayout}
        shareMax={sensorShareMax(leftShare, rightShare)}
        sharePct={leftShare}
        side="LEFT"
      />,
    );
    expect(screen.getByRole('img', { name: '왼발 센서 신호 비율 히트맵' })).toBeInTheDocument();
    expect(
      Array.from(container.querySelectorAll('.sensor-value')).map((node) => node.textContent),
    ).toEqual(['18%', '12%', '8%', '7%', '20%', '15%', '10%', '10%']);
    expect(
      Array.from(container.querySelectorAll('.sensor-label')).map((node) => node.textContent),
    ).toEqual(['S01', 'S02', 'S03', 'S04', 'S05', 'S06', 'S07', 'S08']);
    expect(container.querySelectorAll('.sensor-share')).toHaveLength(0);
    expect(container.querySelectorAll('.sensor-point')).toHaveLength(8);
    expect(screen.getByText('S05 20%')).toBeInTheDocument();
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/압력 중심|총압력|최대 압력|CoP|정상/);
  });

  it('색은 호출자가 넘긴 공통 최대값 기준이라 양발이 같은 축을 쓴다', () => {
    const max = sensorShareMax(leftShare, rightShare);
    expect(max).toBe(30);
    const left = render(
      <SessionShareHeatmap
        layout={labeledLayout}
        shareMax={max}
        sharePct={leftShare}
        side="LEFT"
      />,
    );
    const leftFills = Array.from(left.container.querySelectorAll('.sensor-point')).map((node) =>
      node.getAttribute('fill'),
    );
    left.unmount();
    const right = render(
      <SessionShareHeatmap
        layout={labeledLayout}
        shareMax={max}
        sharePct={rightShare}
        side="RIGHT"
      />,
    );
    const rightFills = Array.from(right.container.querySelectorAll('.sensor-point')).map((node) =>
      node.getAttribute('fill'),
    );
    // 오른발 S01(30 = max) → 최고 단계, 왼발 S01(18) → 18/30*100
    expect(rightFills[0]).toBe(pressureColor(100));
    expect(leftFills[0]).toBe(pressureColor((18 / 30) * 100));
    // 같은 비율(10%)은 발이 달라도 같은 색
    expect(leftFills[6]).toBe(rightFills[6]);
  });

  it('이전 결과(sharePct null)는 안내 문구를 보이고 원은 그대로 두되 수치를 쓰지 않는다', () => {
    const { container } = render(
      <SessionShareHeatmap layout={labeledLayout} sharePct={null} side="RIGHT" />,
    );
    expect(screen.getAllByText(LEGACY_SHARE_MESSAGE).length).toBeGreaterThanOrEqual(1);
    expect(container.querySelectorAll('.sensor-point')).toHaveLength(8);
    expect(container.querySelectorAll('.sensor-point--empty')).toHaveLength(8);
    expect(container.querySelectorAll('.sensor-value')).toHaveLength(0);
    expect(container.querySelector('.heatmap-surface')).toBeNull();
    expect(container.textContent).not.toMatch(/제공 안 됨|데이터 없음/);
  });

  it('meanCoP를 넘기면 평균 압력중심 행과 마커를 그리고 넘기지 않으면 행을 생략한다', () => {
    const withCop = render(
      <SessionShareHeatmap
        layout={labeledLayout}
        meanCoP={{ x: 0.2, y: 0.6 }}
        sharePct={rightShare}
        side="RIGHT"
      />,
    );
    expect(withCop.container.querySelector('.cop-marker')).toHaveAttribute(
      'transform',
      'translate(77 228)',
    );
    expect(screen.getByText('평균 추정 압력중심')).toBeInTheDocument();
    expect(screen.getByText('x 0.2 · y 0.6')).toBeInTheDocument();
    withCop.unmount();

    const withoutCop = render(
      <SessionShareHeatmap layout={labeledLayout} sharePct={rightShare} side="RIGHT" />,
    );
    expect(withoutCop.container.querySelector('.cop-marker')).toBeNull();
    expect(screen.queryByText('평균 추정 압력중심')).toBeNull();
  });

  it('6센서 레이아웃과 레이아웃 없음(로딩)도 처리한다', () => {
    const six: SensorLayoutResponse = {
      version: 'layout-v1-6',
      sensorCount: 6,
      points: labeledLayout.points.slice(0, 6).map((point) => ({ ...point, label: null })),
    };
    const { container } = render(
      <SessionShareHeatmap layout={six} sharePct={[40, 30, 10, 10, 5, 5]} side="LEFT" />,
    );
    expect(container.querySelectorAll('.sensor-point')).toHaveLength(6);
    expect(container.querySelectorAll('.sensor-label')).toHaveLength(0);
    expect(screen.getByText('#1 40%')).toBeInTheDocument();

    const loading = render(
      <SessionShareHeatmap layout={undefined} sharePct={leftShare} side="LEFT" />,
    );
    expect(loading.getByText('센서 배치를 불러오는 중입니다.')).toBeInTheDocument();
  });

  it.each(['error', 'unavailable'] as const)(
    'layoutStatus %s이면 로딩 문구 대신 배치를 확인할 수 없다는 흐린 안내를 보여준다',
    (layoutStatus) => {
      render(
        <SessionShareHeatmap
          layout={undefined}
          layoutStatus={layoutStatus}
          sharePct={leftShare}
          side="LEFT"
        />,
      );
      expect(screen.queryByText('센서 배치를 불러오는 중입니다.')).toBeNull();
      expect(
        screen.getByText('센서 배치를 확인할 수 없어 센서 위치를 표시하지 않습니다.'),
      ).toHaveClass('heatmap-overlay--muted');
    },
  );
});

describe('sensorShare helpers', () => {
  it('sensorShareMax는 null 배열을 건너뛰고 topSensors는 값 내림차순·동률 index순이다', () => {
    expect(sensorShareMax(null, undefined, [1, 5, 3])).toBe(5);
    expect(sensorShareMax(null)).toBe(0);
    expect(topSensors([10, 40, 40, 5])).toEqual([
      { index: 1, value: 40 },
      { index: 2, value: 40 },
    ]);
  });
});

describe('PressureLegend', () => {
  it('가로 범례는 낮음→높음 순서, 실시간 제목과 aria-label을 유지한다', () => {
    const { container } = render(<PressureLegend />);
    const legend = screen.getByRole('group', { name: '상대 신호 범례: 0은 낮음, 100은 높음' });
    expect(legend).toHaveClass('pressure-legend--horizontal');
    expect(screen.getByText('상대 신호 (0–100)')).toBeInTheDocument();
    const labels = Array.from(container.querySelectorAll('.pressure-legend__label')).map(
      (node) => node.textContent,
    );
    expect(labels).toEqual(['낮음', '높음']);
    expect(screen.getByText('0–100 상대값 · 원 아래 %는 센서 신호 비율')).toBeInTheDocument();
    expect(
      container.querySelector<HTMLElement>('.pressure-legend__bar')?.style.background,
    ).toContain('90deg');
  });

  it('세로 범례는 높음이 위, 낮음이 아래이며 share 모드 제목을 쓴다', () => {
    const { container } = render(<PressureLegend mode="share" orientation="vertical" />);
    const legend = screen.getByRole('group', { name: '센서 신호 비율 범례: 낮음에서 높음' });
    expect(legend).toHaveClass('pressure-legend--vertical');
    expect(screen.getByText('센서 신호 비율 (%)')).toBeInTheDocument();
    const labels = Array.from(container.querySelectorAll('.pressure-legend__label')).map(
      (node) => node.textContent,
    );
    expect(labels).toEqual(['높음', '낮음']);
    expect(
      container.querySelector<HTMLElement>('.pressure-legend__bar')?.style.background,
    ).toContain('0deg');
    expect(container.textContent).not.toMatch(/압력 강도|CoP|정상/);
  });

  it('title prop으로 제목을 바꿀 수 있다', () => {
    render(<PressureLegend title="상대 신호" />);
    expect(screen.getByText('상대 신호')).toBeInTheDocument();
  });
});
