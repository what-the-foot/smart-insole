import { render, screen } from '@testing-library/react';
import type { FootRealtimeData, SensorLayoutResponse } from '../../api/types';
import { FootPressureHeatmap } from './FootPressureHeatmap';

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

    expect(screen.getByRole('img', { name: '오른발 족압 히트맵' })).toBeInTheDocument();
    expect(screen.getByText('마지막 수신값을 흐리게 표시합니다.')).toBeInTheDocument();
    expect(screen.getByText('데이터 끊김')).toBeInTheDocument();
    expect(container.querySelectorAll('.sensor-point')).toHaveLength(6);
    expect(screen.getByText('100')).toBeInTheDocument();
  });

  it('발끝→뒤꿈치 y축을 그대로 사용하고 왼발의 x축과 외곽선·압력 중심만 좌우 반전한다', () => {
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
