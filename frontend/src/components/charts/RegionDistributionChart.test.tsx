import { render, screen } from '@testing-library/react';
import { RegionDistributionChart } from './RegionDistributionChart';

const rows = [
  { label: '후족부', left: 0.4, right: 0.5 },
  { label: '중족부', left: 0.2, right: null },
  { label: '전족부·발가락', left: 0.4, right: 0.5 },
];

describe('RegionDistributionChart', () => {
  it('부위마다 왼발·오른발 비율을 텍스트로 적고 가장 큰 값을 100% 길이로 그린다', () => {
    const { container } = render(<RegionDistributionChart rows={rows} />);

    expect(screen.getByRole('group', { name: '부위별 센서 신호 비율' })).toBeInTheDocument();
    expect(screen.getByText('후족부')).toBeInTheDocument();
    expect(screen.getByText('중족부')).toBeInTheDocument();
    expect(screen.getByText('전족부·발가락')).toBeInTheDocument();
    expect(screen.getAllByText('40%')).toHaveLength(2);
    expect(screen.getAllByText('50%')).toHaveLength(2);
    expect(screen.getByText('20%')).toBeInTheDocument();

    const fills = container.querySelectorAll<HTMLElement>('.region-chart__fill');
    expect(fills).toHaveLength(6);
    expect(fills[0]).toHaveStyle({ width: '80%' });
    expect(fills[1]).toHaveStyle({ width: '100%' });
    expect(fills[2]).toHaveStyle({ width: '40%' });
  });

  it('값이 없으면 빈 막대와 함께 "제공 안 됨"을 적는다', () => {
    const { container } = render(<RegionDistributionChart rows={rows} />);

    const empty = screen.getByText('제공 안 됨');
    expect(empty).toHaveClass('region-chart__value--empty');
    const fills = container.querySelectorAll<HTMLElement>('.region-chart__fill');
    expect(fills[3]).toHaveStyle({ width: '0%' });
  });

  it('색상 외에 각 막대 옆에 발 방향 텍스트를 함께 둔다', () => {
    const { container } = render(<RegionDistributionChart rows={rows.slice(0, 1)} />);

    const sides = Array.from(container.querySelectorAll('.region-chart__side')).map(
      (node) => node.textContent,
    );
    expect(sides).toEqual(['왼발', '오른발']);
  });
});
