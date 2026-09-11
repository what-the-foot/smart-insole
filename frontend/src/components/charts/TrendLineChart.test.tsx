import { render, screen, within } from '@testing-library/react';
import { TrendLineChart } from './TrendLineChart';

const points = [
  { label: '5/12', value: 82 },
  { label: '5/13', value: 78 },
  { label: '5/14', value: null },
  { label: '5/15', value: 85 },
  { label: '5/16', value: 80.5 },
];

describe('TrendLineChart', () => {
  it('요약 문구를 가진 img 역할과 sr-only 표를 함께 제공한다', () => {
    render(<TrendLineChart ariaSummary="최근 5회 데이터 품질 점수" points={points} unit="점" />);

    expect(screen.getByRole('img', { name: '최근 5회 데이터 품질 점수' })).toBeInTheDocument();
    expect(screen.getByText('단위: 점')).toBeInTheDocument();

    const table = screen.getByRole('table');
    expect(within(table).getByRole('columnheader', { name: '측정' })).toBeInTheDocument();
    expect(within(table).getByRole('columnheader', { name: '값 (점)' })).toBeInTheDocument();
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(6);
    expect(within(table).getByRole('rowheader', { name: '5/14' })).toBeInTheDocument();
    expect(within(table).getByRole('cell', { name: '제공 안 됨' })).toBeInTheDocument();
    expect(within(table).getByRole('cell', { name: '80.5' })).toBeInTheDocument();
  });

  it('null 값에서 선을 끊고(보간 없음) 값이 있는 점에만 점을 찍는다', () => {
    const { container } = render(<TrendLineChart ariaSummary="요약" points={points} unit="점" />);

    expect(container.querySelectorAll('.trend-chart__line')).toHaveLength(2);
    expect(container.querySelectorAll('.trend-chart__dot')).toHaveLength(4);
    const xLabels = Array.from(container.querySelectorAll('.trend-chart__x-label')).map(
      (node) => node.textContent,
    );
    expect(xLabels).toEqual(['5/12', '5/13', '5/14', '5/15', '5/16']);
    expect(container.querySelector('.trend-chart__x-label--gap')).toHaveTextContent('5/14');
    expect(container.querySelectorAll('.trend-chart__tick')).toHaveLength(3);
  });

  it('값이 하나도 없으면 SVG 대신 빈 상태 문구를 보여 준다', () => {
    const { container } = render(
      <TrendLineChart ariaSummary="값 없음" points={[{ label: '5/12', value: null }]} unit="점" />,
    );

    expect(screen.getByText('표시할 값이 없습니다.')).toBeInTheDocument();
    expect(container.querySelector('svg')).toBeNull();
    expect(screen.getByRole('cell', { name: '제공 안 됨' })).toBeInTheDocument();
  });

  it('점이 많으면 x 라벨을 일부만 표시하되 마지막 라벨은 유지한다', () => {
    const many = Array.from({ length: 12 }, (_, index) => ({
      label: `D${index + 1}`,
      value: index,
    }));
    const { container } = render(<TrendLineChart ariaSummary="요약" points={many} unit="점" />);

    const xLabels = Array.from(container.querySelectorAll('.trend-chart__x-label')).map(
      (node) => node.textContent,
    );
    expect(xLabels).toEqual(['D1', 'D3', 'D5', 'D7', 'D9', 'D11', 'D12']);
    expect(container.querySelectorAll('.trend-chart__dot')).toHaveLength(12);
  });
});
