import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { measurementApi } from '../api/services';
import { HistoryPage } from './HistoryPage';

describe('HistoryPage', () => {
  it('날짜·최소 품질·6개 알려진 패턴 필터를 전송하고 주요 패턴을 표시한다', async () => {
    const user = userEvent.setup();
    const list = vi.spyOn(measurementApi, 'list').mockResolvedValue({
      items: [
        {
          sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
          status: 'COMPLETED',
          leftDeviceId: 'b4b96290-ad73-42d9-ae21-1446f1258861',
          rightDeviceId: '64eb539f-4b48-44f6-bb30-d26861463ca6',
          sampleRateHz: 100,
          sourceType: 'SIMULATED',
          memo: '평지 보행',
          dataQualityScore: 91,
          startedAt: '2026-09-01T07:00:00Z',
          endedAt: '2026-09-01T07:01:00Z',
          createdAt: '2026-09-01T07:00:00Z',
          primaryPatternCode: 'SHORT_CONTACT_TIME',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <HistoryPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('짧은 접촉 시간 경향')).toBeInTheDocument();
    const pattern = screen.getByRole('combobox', { name: '관찰 패턴' });
    expect(within(pattern).getAllByRole('option')).toHaveLength(7);
    await user.type(screen.getByLabelText('시작 날짜'), '2026-09-01');
    await user.type(screen.getByLabelText('종료 날짜'), '2026-09-02');
    await user.type(screen.getByLabelText('최소 품질 점수'), '80');
    await user.selectOptions(pattern, 'LOW_DATA_QUALITY');
    await user.click(screen.getByRole('button', { name: '필터 적용' }));

    await waitFor(() => {
      expect(list).toHaveBeenLastCalledWith({
        page: 0,
        size: 10,
        from: new Date('2026-09-01T00:00:00').toISOString(),
        to: new Date('2026-09-02T23:59:59.999').toISOString(),
        minQualityScore: 80,
        patternCode: 'LOW_DATA_QUALITY',
      });
    });
  });

  it('시작 날짜가 종료 날짜보다 늦으면 요청하지 않는다', async () => {
    const user = userEvent.setup();
    const list = vi.spyOn(measurementApi, 'list').mockResolvedValue({
      items: [], page: 0, size: 10, totalElements: 0, totalPages: 0,
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter><HistoryPage /></MemoryRouter>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    await user.type(screen.getByLabelText('시작 날짜'), '2026-09-03');
    await user.type(screen.getByLabelText('종료 날짜'), '2026-09-02');
    await user.click(screen.getByRole('button', { name: '필터 적용' }));

    expect(screen.getByRole('alert')).toHaveTextContent('시작 날짜는 종료 날짜보다 늦을 수 없습니다.');
    expect(list).toHaveBeenCalledTimes(1);
  });
});
