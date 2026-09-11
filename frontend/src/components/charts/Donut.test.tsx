import { render, screen } from '@testing-library/react';
import { Donut } from './Donut';

describe('Donut', () => {
  it('요약 문구를 가진 img 역할과 좌우 범례·가운데 텍스트를 렌더링한다', () => {
    const { container } = render(
      <Donut ariaLabel="왼발 52%, 오른발 48%" centerLabel="52 : 48" leftPct={52} rightPct={48} />,
    );

    expect(screen.getByRole('img', { name: '왼발 52%, 오른발 48%' })).toBeInTheDocument();
    expect(screen.getByText('52 : 48')).toBeInTheDocument();
    expect(screen.getByText('왼발')).toBeInTheDocument();
    expect(screen.getByText('오른발')).toBeInTheDocument();
    expect(screen.getByText('52%')).toBeInTheDocument();
    expect(screen.getByText('48%')).toBeInTheDocument();

    const left = container.querySelector('.donut__arc--left');
    const right = container.querySelector('.donut__arc--right');
    expect(left).toHaveAttribute('stroke-dasharray', '52 48');
    expect(left).toHaveAttribute('stroke-dashoffset', '0');
    expect(right).toHaveAttribute('stroke-dasharray', '48 52');
    expect(right).toHaveAttribute('stroke-dashoffset', '-52');
  });

  it('합이 100이 아니면 합 기준으로 정규화한다', () => {
    const { container } = render(
      <Donut ariaLabel="왼발 3, 오른발 1" centerLabel="3 : 1" leftPct={3} rightPct={1} />,
    );

    expect(screen.getByText('75%')).toBeInTheDocument();
    expect(screen.getByText('25%')).toBeInTheDocument();
    expect(container.querySelector('.donut__arc--left')).toHaveAttribute(
      'stroke-dasharray',
      '75 25',
    );
  });

  it('값이 모두 0이면 빈 링과 "제공 안 됨"을 표시한다', () => {
    const { container } = render(
      <Donut ariaLabel="좌우 비율 값 없음" centerLabel="—" leftPct={0} rightPct={0} />,
    );

    expect(container.querySelector('.donut')).toHaveClass('donut--empty');
    expect(screen.getAllByText('제공 안 됨')).toHaveLength(2);
  });
});
