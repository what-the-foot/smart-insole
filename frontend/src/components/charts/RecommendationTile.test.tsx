import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { RecommendationTile } from './RecommendationTile';

describe('RecommendationTile', () => {
  it('소요 시간·제목·요약을 표시하고 가이드 상세로 링크한다', () => {
    render(
      <MemoryRouter>
        <RecommendationTile
          code="ANKLE_STABILITY_BASIC"
          durationMinutes={10}
          icon={<svg data-testid="tile-icon" />}
          summary="발목 주변 근육을 천천히 움직이는 기본 동작입니다."
          title="발목 안정 기본 동작"
        />
      </MemoryRouter>,
    );

    expect(screen.getByText('약 10분')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 3, name: '발목 안정 기본 동작' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('발목 주변 근육을 천천히 움직이는 기본 동작입니다.'),
    ).toBeInTheDocument();

    const link = screen.getByRole('link', { name: '가이드 확인' });
    expect(link).toHaveAttribute('href', '/recommendations/ANKLE_STABILITY_BASIC');
    expect(link).toHaveAccessibleDescription('발목 안정 기본 동작');
    expect(screen.getByTestId('tile-icon').parentElement).toHaveAttribute('aria-hidden', 'true');
  });

  it('코드를 URL에 안전하게 인코딩하고 아이콘이 없으면 아트 영역을 그리지 않는다', () => {
    const { container } = render(
      <MemoryRouter>
        <RecommendationTile
          code="RE MEASURE/GUIDE"
          durationMinutes={5}
          summary="요약"
          title="다시 측정 안내"
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: '가이드 확인' })).toHaveAttribute(
      'href',
      '/recommendations/RE%20MEASURE%2FGUIDE',
    );
    expect(container.querySelector('.reco-tile__art')).toBeNull();
  });
});
