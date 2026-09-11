import { render, screen } from '@testing-library/react';
import { Sparkline } from './Sparkline';

describe('Sparkline', () => {
  it('값 개수와 최근 값을 담은 기본 요약으로 img 역할을 제공한다', () => {
    const { container } = render(<Sparkline values={[1, 2, null, 3]} />);

    expect(screen.getByRole('img', { name: '값 3개의 변화 흐름, 최근 3' })).toBeInTheDocument();
    // [1,2]는 선, 고립된 3은 점, 마지막 값에는 강조 점
    expect(container.querySelectorAll('.sparkline__line')).toHaveLength(1);
    expect(container.querySelectorAll('.sparkline__point')).toHaveLength(1);
    expect(container.querySelectorAll('.sparkline__dot')).toHaveLength(1);
    expect(container.querySelector('svg')).toHaveAttribute('viewBox', '0 0 96 28');
  });

  it('요약 문구와 크기를 바꿀 수 있다', () => {
    const { container } = render(
      <Sparkline
        ariaLabel="최근 4회 접촉 시간"
        height={20}
        values={[250, 260, 255, 258]}
        width={60}
      />,
    );

    expect(screen.getByRole('img', { name: '최근 4회 접촉 시간' })).toBeInTheDocument();
    expect(container.querySelector('svg')).toHaveAttribute('viewBox', '0 0 60 20');
    expect(container.querySelectorAll('.sparkline__line')).toHaveLength(1);
  });

  it('값이 없으면 빈 상태 클래스와 문구를 사용한다', () => {
    const { container } = render(<Sparkline values={[null, null]} />);

    expect(screen.getByRole('img', { name: '표시할 값이 없습니다.' })).toHaveClass(
      'sparkline--empty',
    );
    expect(container.querySelector('.sparkline__line')).toBeNull();
    expect(container.querySelector('.sparkline__dot')).toBeNull();
  });
});
