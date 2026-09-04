import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { measurementApi } from '../api/services';
import { ResultPage } from './ResultPage';

const sessionId = '5803f871-9fca-4a7f-a2c7-9b567a92a6cf';

describe('ResultPage', () => {
  it('취소된 측정에서는 비활성 결과 쿼리의 pending 상태 대신 종료 안내를 표시한다', async () => {
    vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'CANCELLED',
      leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
      rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      adcMax: 4095,
      memo: null,
      endedAt: '2026-09-02T07:01:00Z',
      createdAt: '2026-09-02T07:00:00Z',
    });
    const result = vi.spyOn(measurementApi, 'result');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/measurements/${sessionId}/result`]}>
          <Routes>
            <Route element={<ResultPage />} path="/measurements/:sessionId/result" />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: '취소된 측정입니다' })).toBeInTheDocument();
    expect(result).not.toHaveBeenCalled();
  });
});
