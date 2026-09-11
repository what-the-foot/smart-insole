import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { measurementApi } from '../api/services';
import type { MeasurementHistoryItem, MeasurementSessionPage } from '../api/types';
import { LiveEntryPage } from './LiveEntryPage';

const item = (
  sessionId: string,
  status: MeasurementHistoryItem['status'],
): MeasurementHistoryItem => ({
  sessionId,
  status,
  leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
  rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
  sampleRateHz: 50,
  sourceType: 'DEVICE',
  createdAt: '2026-09-01T07:00:00Z',
  primaryPatternCode: null,
});

const page = (items: MeasurementHistoryItem[]): MeasurementSessionPage => ({
  items,
  page: 0,
  size: 1,
  totalElements: items.length,
  totalPages: items.length ? 1 : 0,
});

const renderEntry = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/measurements/live']}>
        <Routes>
          <Route element={<LiveEntryPage />} path="/measurements/live" />
          <Route element={<h1>새 측정 화면</h1>} path="/measurements/new" />
          <Route element={<h1>실시간 화면</h1>} path="/measurements/:sessionId/live" />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('LiveEntryPage', () => {
  it('진행 중(MEASURING) 세션이 있으면 그 실시간 화면으로 이동한다', async () => {
    const list = vi
      .spyOn(measurementApi, 'list')
      .mockResolvedValue(page([item('5803f871-9fca-4a7f-a2c7-9b567a92a6cf', 'MEASURING')]));
    renderEntry();

    expect(await screen.findByRole('heading', { name: '실시간 화면' })).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith({ page: 0, size: 1, status: 'MEASURING' });
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('MEASURING이 없으면 CREATED 세션을 찾아 이동한다', async () => {
    const list = vi
      .spyOn(measurementApi, 'list')
      .mockImplementation((params) =>
        Promise.resolve(
          params.status === 'CREATED'
            ? page([item('11111111-1111-4111-8111-111111111111', 'CREATED')])
            : page([]),
        ),
      );
    renderEntry();

    expect(await screen.findByRole('heading', { name: '실시간 화면' })).toBeInTheDocument();
    await waitFor(() => expect(list).toHaveBeenCalledWith({ page: 0, size: 1, status: 'CREATED' }));
  });

  it('진행 중이거나 준비된 세션이 없으면 새 측정으로 이동한다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue(page([]));
    renderEntry();

    expect(await screen.findByRole('heading', { name: '새 측정 화면' })).toBeInTheDocument();
  });

  it('목록 조회가 실패하면 다시 시도할 수 있다', async () => {
    vi.spyOn(measurementApi, 'list').mockRejectedValue(new Error('서버에 연결할 수 없습니다.'));
    renderEntry();

    expect(await screen.findByText('서버에 연결할 수 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });
});
