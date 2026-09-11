import { render, screen } from '@testing-library/react';
import { ContactTimeBars } from './ContactTimeBars';

describe('ContactTimeBars', () => {
  it('좌우 접촉 시간을 밀리초 텍스트로 적고 긴 쪽을 100% 길이로 그린다', () => {
    const { container } = render(<ContactTimeBars leftMs={256} rightMs={243} />);

    expect(screen.getByRole('group', { name: '좌우 접촉 시간' })).toBeInTheDocument();
    expect(screen.getByText('256밀리초')).toBeInTheDocument();
    expect(screen.getByText('243밀리초')).toBeInTheDocument();
    expect(screen.getByText('차이 13밀리초')).toBeInTheDocument();
    expect(screen.getByText('왼발')).toBeInTheDocument();
    expect(screen.getByText('오른발')).toBeInTheDocument();

    const fills = container.querySelectorAll<HTMLElement>('.contact-bars__fill');
    expect(fills).toHaveLength(2);
    expect(fills[0]).toHaveStyle({ width: '100%' });
    expect(parseFloat(fills[1]?.style.width ?? '0')).toBeCloseTo((243 / 256) * 100, 3);
  });

  it('값이 없으면 "제공 안 됨"을 적고 차이도 계산하지 않는다', () => {
    const { container } = render(<ContactTimeBars leftMs={null} rightMs={243} />);

    expect(screen.getByText('제공 안 됨')).toHaveClass('contact-bars__value--empty');
    expect(screen.getByText('차이 제공 안 됨')).toBeInTheDocument();
    const fills = container.querySelectorAll<HTMLElement>('.contact-bars__fill');
    expect(fills[0]).toHaveStyle({ width: '0%' });
    expect(fills[1]).toHaveStyle({ width: '100%' });
  });
});
