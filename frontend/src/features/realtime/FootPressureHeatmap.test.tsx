import { render, screen } from '@testing-library/react';
import type { FootRealtimeData, SensorLayoutResponse } from '../../api/types';
import { FootPressureHeatmap } from './FootPressureHeatmap';
import { sensorSharePercent } from './sensorShare';

const layout: SensorLayoutResponse = {
  version: 'layout-v1-6',
  sensorCount: 6,
  points: [
    { index: 0, x: 0.5, y: 0.9, region: 'HEEL', medialLateral: 'CENTER' },
    { index: 1, x: 0.35, y: 0.7, region: 'MIDFOOT', medialLateral: 'MEDIAL' },
    { index: 2, x: 0.65, y: 0.7, region: 'MIDFOOT', medialLateral: 'LATERAL' },
    { index: 3, x: 0.3, y: 0.35, region: 'FOREFOOT', medialLateral: 'MEDIAL' },
    { index: 4, x: 0.7, y: 0.35, region: 'FOREFOOT', medialLateral: 'LATERAL' },
    { index: 5, x: 0.5, y: 0.1, region: 'TOE', medialLateral: 'CENTER' },
  ],
};

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

const lastRight: NonNullable<FootRealtimeData> = {
  connected: true,
  lastSequence: 111,
  deviceTimeMs: 1110,
  sensorValues: [0, 20, 40, 60, 80, 100],
  totalPressure: 300,
  cop: { x: 0.2, y: 0.6 },
  contactState: 'CONTACT',
  lastReceivedAt: '2026-09-02T07:11:10.492Z',
};

describe('FootPressureHeatmap', () => {
  it('한쪽 발이 끊겨도 마지막 6센서 값을 유지하고 텍스트 경고를 제공한다', () => {
    const { container } = render(
      <FootPressureHeatmap data={lastRight} disconnected layout={layout} side="RIGHT" />,
    );

    expect(screen.getByRole('img', { name: '오른발 센서 신호 히트맵' })).toBeInTheDocument();
    expect(screen.getByText('마지막 수신값을 흐리게 표시합니다.')).toBeInTheDocument();
    expect(screen.getByText('데이터 끊김')).toBeInTheDocument();
    expect(container.querySelectorAll('.sensor-point')).toHaveLength(6);
    expect(screen.getByText('100')).toBeInTheDocument();
  });

  it('센서별 share를 센서/전체합×100의 반올림 정수로 표시하고 임계 판단은 하지 않는다', () => {
    const { container } = render(
      <FootPressureHeatmap data={lastRight} disconnected={false} layout={layout} side="RIGHT" />,
    );
    const shares = Array.from(container.querySelectorAll('.sensor-share')).map(
      (node) => node.textContent,
    );
    expect(shares).toEqual(['0%', '7%', '13%', '20%', '27%', '33%']);
    expect(container.textContent).not.toMatch(/압력 중심|총압력|최대 압력|CoP/);
    expect(screen.getByText('추정 압력중심')).toBeInTheDocument();
    expect(screen.getByText('상대 총 신호')).toBeInTheDocument();
  });

  it('전체합이 0이거나 데이터가 없으면 share를 표시하지 않는다', () => {
    const zero = render(
      <FootPressureHeatmap
        data={{ ...lastRight, sensorValues: [0, 0, 0, 0, 0, 0], totalPressure: 0, cop: null }}
        disconnected={false}
        layout={layout}
        side="RIGHT"
      />,
    );
    expect(zero.container.querySelectorAll('.sensor-share')).toHaveLength(0);
    expect(sensorSharePercent(10, 0)).toBeNull();
    zero.unmount();

    const empty = render(
      <FootPressureHeatmap data={null} disconnected={false} layout={layout} side="LEFT" />,
    );
    expect(empty.container.querySelectorAll('.sensor-share')).toHaveLength(0);
    expect(empty.container.querySelectorAll('.sensor-point')).toHaveLength(6);
  });

  it('layout-s01s08-v1의 센서 라벨 S01..S08을 함께 표시한다', () => {
    const { container } = render(
      <FootPressureHeatmap
        data={{ ...lastRight, sensorValues: [10, 10, 10, 10, 10, 10, 20, 20], totalPressure: 100 }}
        disconnected={false}
        layout={labeledLayout}
        side="LEFT"
      />,
    );
    expect(Array.from(container.querySelectorAll('.sensor-label')).map((n) => n.textContent)).toEqual(
      ['S01', 'S02', 'S03', 'S04', 'S05', 'S06', 'S07', 'S08'],
    );
    expect(container.querySelectorAll('.sensor-share')[7]).toHaveTextContent('20%');
  });

  it('발끝→뒤꿈치 y축을 그대로 사용하고 왼발의 x축과 외곽선·추정 압력중심만 좌우 반전한다', () => {
    const left = render(
      <FootPressureHeatmap data={lastRight} disconnected={false} layout={layout} side="LEFT" />,
    );
    const leftSensors = left.container.querySelectorAll('.sensor-point');

    expect(leftSensors[0]).toHaveAttribute('cy', '321');
    expect(leftSensors[3]).toHaveAttribute('cx', '139.5');
    expect(left.container.querySelector('.foot-outline')).toHaveAttribute(
      'transform',
      'translate(229 0) scale(-1 1)',
    );
    expect(left.container.querySelector('.cop-marker')).toHaveAttribute(
      'transform',
      'translate(152 228)',
    );
    expect(left.container.querySelector('.sensor-value')).not.toHaveAttribute('transform');
    left.unmount();

    const right = render(
      <FootPressureHeatmap data={lastRight} disconnected={false} layout={layout} side="RIGHT" />,
    );
    expect(right.container.querySelectorAll('.sensor-point')[3]).toHaveAttribute('cx', '89.5');
    expect(right.container.querySelector('.foot-outline')).not.toHaveAttribute('transform');
    expect(right.container.querySelector('.cop-marker')).toHaveAttribute(
      'transform',
      'translate(77 228)',
    );
  });
});
