import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { recommendationApi } from '../api/services';
import { RecommendationPage } from './RecommendationPage';

describe('RecommendationPage', () => {
  it('직접 진입하거나 새로고침해도 코드로 상세 가이드를 다시 조회한다', async () => {
    const getGuide = vi.spyOn(recommendationApi, 'get').mockResolvedValue({
      code: 'ANKLE_STABILITY_BASIC',
      title: '기본 발목 안정화 운동',
      purpose: '발목 주변을 편안한 범위에서 움직입니다.',
      instructions: ['의자를 잡고 바르게 섭니다.', '발뒤꿈치를 천천히 들어 올립니다.'],
      durationMinutes: 5,
      cautionText: '통증이 있으면 즉시 중단하세요.',
      relatedPatternCodes: ['LEFT_RIGHT_ASYMMETRY', 'REARFOOT_LOAD_TENDENCY', 'SHORT_CONTACT_TIME'],
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/recommendations/ANKLE_STABILITY_BASIC']}>
          <Routes>
            <Route element={<RecommendationPage />} path="/recommendations/:code" />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(
      await screen.findByRole('heading', { name: '기본 발목 안정화 운동' }),
    ).toBeInTheDocument();
    expect(getGuide).toHaveBeenCalledWith('ANKLE_STABILITY_BASIC');
    expect(screen.getByText('발뒤꿈치를 천천히 들어 올립니다.')).toBeInTheDocument();
    expect(screen.getByText('좌우 비대칭 경향')).toBeInTheDocument();
    expect(screen.getByText('후족부 하중 경향')).toBeInTheDocument();
    // 이전 알고리즘 코드는 그대로 표시하되 이전 분석 라벨임을 밝힌다.
    expect(screen.getByText('짧은 접촉 시간 경향 (이전 분석)')).toBeInTheDocument();
    expect(screen.getByText('통증이 있으면 즉시 중단하세요.')).toBeInTheDocument();
  });
});
