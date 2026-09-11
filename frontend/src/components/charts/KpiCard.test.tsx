import { render, screen } from '@testing-library/react';
import { KpiCard } from './KpiCard';

describe('KpiCard', () => {
  it('라벨·숫자 값(천 단위 구분)·단위·보조 문구·배지를 표시한다', () => {
    const { container } = render(
      <KpiCard
        badge={{ tone: 'warning', text: '확인 필요' }}
        icon={<svg data-testid="kpi-icon" />}
        label="데이터 품질"
        sub="플래그 2개"
        tone="warning"
        unit="점"
        value={1234.5}
      />,
    );

    expect(screen.getByText('데이터 품질')).toBeInTheDocument();
    expect(screen.getByText('1,234.5')).toBeInTheDocument();
    expect(screen.getByText('점')).toBeInTheDocument();
    expect(screen.getByText('플래그 2개')).toBeInTheDocument();
    expect(screen.getByText('확인 필요')).toHaveClass('status-badge', 'status-badge--warning');
    expect(container.querySelector('.kpi-card')).toHaveClass('kpi-card--warning');
    expect(screen.getByTestId('kpi-icon').parentElement).toHaveAttribute('aria-hidden', 'true');
  });

  it('문자열 값은 그대로 두고 선택 항목이 없으면 렌더링하지 않는다', () => {
    const { container } = render(<KpiCard icon={null} label="관찰된 패턴" value="2종" />);

    expect(screen.getByText('2종')).toBeInTheDocument();
    expect(container.querySelector('.kpi-card__unit')).toBeNull();
    expect(container.querySelector('.kpi-card__sub')).toBeNull();
    expect(container.querySelector('.status-badge')).toBeNull();
    expect(container.querySelector('.kpi-card')).toHaveClass('kpi-card--primary');
  });
});
