import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { measurementApi } from '../api/services';
import type { MeasurementSessionPage } from '../api/types';
import { ResultsEntryPage } from './ResultsEntryPage';

const renderEntry = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/results']}>
        <Routes>
          <Route element={<ResultsEntryPage />} path="/results" />
          <Route element={<h1>결과 화면</h1>} path="/measurements/:sessionId/result" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

const emptyPage: MeasurementSessionPage = {
  items: [],
  page: 0,
  size: 1,
  totalElements: 0,
  totalPages: 0,
};

describe('ResultsEntryPage', () => {
  it('가장 최근 완료된 측정의 결과 화면으로 이동한다', async () => {
    const list = vi.spyOn(measurementApi, 'list').mockResolvedValue({
      ...emptyPage,
      items: [
        {
          sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
          status: 'COMPLETED',
          leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
          rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
          sampleRateHz: 50,
          sourceType: 'DEVICE',
          createdAt: '2026-09-01T07:00:00Z',
          primaryPatternCode: 'MEDIAL_LOAD_TENDENCY',
        },
      ],
      totalElements: 1,
      totalPages: 1,
    });
    renderEntry();

    expect(await screen.findByRole('heading', { name: '결과 화면' })).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith({ page: 0, size: 1, status: 'COMPLETED' });
  });

  it('완료된 측정이 없으면 새 측정으로 안내한다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue(emptyPage);
    renderEntry();

    expect(
      await screen.findByRole('heading', { name: '아직 완료된 측정이 없어요' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '새 측정' })).toHaveAttribute(
      'href',
      '/measurements/new',
    );
  });
});
