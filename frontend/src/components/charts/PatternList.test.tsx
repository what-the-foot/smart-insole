import { render, screen } from '@testing-library/react';
import { PatternList, type PatternListItem } from './PatternList';

const patterns: PatternListItem[] = [
  {
    code: 'MEDIAL_LOAD_TENDENCY',
    title: '내측 하중 경향',
    message: '왼발 내측 센서 신호가 외측보다 자주 높게 관찰되었습니다.',
    severity: 'CAUTION',
    observationLevel: 'REPEATEDLY_OBSERVED',
    occurrenceText: '발생 비율 62% (13/21 걸음)',
  },
  {
    code: 'LEFT_RIGHT_ASYMMETRY',
    title: '좌우 비대칭 경향 (이전 분석)',
    message: '좌우 접촉 시간 차이가 관찰되었습니다.',
    severity: 'INFO',
    observationLevel: null,
  },
];

describe('PatternList', () => {
  it('백엔드 제목·문구를 그대로 보여 주고 심각도·관찰 단계 배지를 붙인다', () => {
    const renderIcon = vi.fn((code: string) => <svg data-testid={`icon-${code}`} />);
    const { container } = render(<PatternList patterns={patterns} renderIcon={renderIcon} />);

    expect(screen.getByRole('list', { name: '관찰된 패턴' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByRole('heading', { level: 3, name: '내측 하중 경향' })).toBeInTheDocument();
    expect(
      screen.getByText('왼발 내측 센서 신호가 외측보다 자주 높게 관찰되었습니다.'),
    ).toBeInTheDocument();
    expect(screen.getByText('발생 비율 62% (13/21 걸음)')).toBeInTheDocument();

    expect(screen.getByText('주의')).toHaveClass('status-badge--warning');
    expect(screen.getByText('반복 관찰')).toHaveClass('status-badge--warning');
    expect(screen.getByText('참고')).toHaveClass('status-badge--info');
    expect(container.querySelectorAll('.status-badge')).toHaveLength(3);

    expect(renderIcon).toHaveBeenCalledWith('MEDIAL_LOAD_TENDENCY');
    expect(renderIcon).toHaveBeenCalledWith('LEFT_RIGHT_ASYMMETRY');
    expect(screen.getByTestId('icon-MEDIAL_LOAD_TENDENCY').parentElement).toHaveAttribute(
      'aria-hidden',
      'true',
    );
    expect(container.querySelector('.pattern-card')).toBeNull();
    expect(container.querySelector('.pattern-row--caution')).toBeInTheDocument();
  });

  it('관찰 단계가 없는 이전 분석 항목은 관찰 단계 배지를 생략한다', () => {
    render(<PatternList patterns={patterns.slice(1)} />);

    expect(screen.queryByText('관찰되지 않음')).not.toBeInTheDocument();
    expect(screen.queryByText(/관찰 단계 미제공/)).not.toBeInTheDocument();
    expect(screen.getByText('참고')).toBeInTheDocument();
  });

  it('패턴이 없으면 빈 상태 문구만 보여 준다', () => {
    render(<PatternList patterns={[]} />);

    expect(screen.getByText('표시할 관찰 패턴이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });
});
