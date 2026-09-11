import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { measurementApi } from '../api/services';
import type { MeasurementHistoryItem } from '../api/types';
import { HistoryPage } from './HistoryPage';

const renderPage = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

const completed: MeasurementHistoryItem = {
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
  primaryPatternCode: 'REARFOOT_LOAD_TENDENCY',
};

describe('HistoryPage', () => {
  it('날짜·최소 품질·rule-v1.2.0 패턴 6종 필터를 전송하고 주요 패턴·sourceType을 표시한다', async () => {
    const user = userEvent.setup();
    const list = vi.spyOn(measurementApi, 'list').mockResolvedValue({
      items: [completed],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
    renderPage();

    // 패턴 라벨은 필터 옵션에도 있으므로 기록 행 안에서 확인한다.
    const link = await screen.findByRole('link', { name: /측정 상세 보기/ });
    const row = link.closest<HTMLElement>('[role="row"]');
    if (!row) throw new Error('기록 링크는 행 안에 있어야 합니다.');
    expect(within(row).getByText('후족부 하중 경향')).toBeInTheDocument();
    expect(within(row).getByText('시뮬레이션 · 100Hz')).toBeInTheDocument();
    const pattern = screen.getByRole('combobox', { name: '관찰 패턴' });
    const options = within(pattern).getAllByRole('option');
    expect(options).toHaveLength(7);
    expect(options.slice(1).map((option) => option.getAttribute('value'))).toEqual([
      'MEDIAL_LOAD_TENDENCY',
      'LATERAL_LOAD_TENDENCY',
      'LEFT_RIGHT_ASYMMETRY',
      'LOW_HALLUX_SIGNAL',
      'FOREFOOT_LOAD_TENDENCY',
      'REARFOOT_LOAD_TENDENCY',
    ]);
    await user.type(screen.getByLabelText('시작 날짜'), '2026-09-01');
    await user.type(screen.getByLabelText('종료 날짜'), '2026-09-02');
    await user.type(screen.getByLabelText('최소 품질 점수'), '80');
    await user.selectOptions(pattern, 'LOW_HALLUX_SIGNAL');
    await user.click(screen.getByRole('button', { name: '필터 적용' }));

    await waitFor(() => {
      expect(list).toHaveBeenLastCalledWith({
        page: 0,
        size: 10,
        from: new Date('2026-09-01T00:00:00').toISOString(),
        to: new Date('2026-09-02T23:59:59.999').toISOString(),
        minQualityScore: 80,
        patternCode: 'LOW_HALLUX_SIGNAL',
      });
    });
    expect(screen.getByText('필터 4개 적용')).toBeInTheDocument();
  });

  it('시작 날짜가 종료 날짜보다 늦으면 요청하지 않는다', async () => {
    const user = userEvent.setup();
    const list = vi.spyOn(measurementApi, 'list').mockResolvedValue({
      items: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    });
    renderPage();
    await waitFor(() => expect(list).toHaveBeenCalledTimes(1));
    await user.type(screen.getByLabelText('시작 날짜'), '2026-09-03');
    await user.type(screen.getByLabelText('종료 날짜'), '2026-09-02');
    await user.click(screen.getByRole('button', { name: '필터 적용' }));

    expect(screen.getByRole('alert')).toHaveTextContent(
      '시작 날짜는 종료 날짜보다 늦을 수 없습니다.',
    );
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('계약 1.2.0 요약 지표와 품질 단계 배지를 표시하고, null이면 제공 안 됨으로 구분한다', async () => {
    vi.spyOn(measurementApi, 'list').mockResolvedValue({
      items: [
        {
          ...completed,
          algorithmVersion: 'rule-v1.4.0',
          dataQualityLevel: 'GOOD',
          symmetryIndex: 12.4,
          cadence: 104,
          leftContactTimeMs: 610,
          rightContactTimeMs: 598,
          validStepCount: 42,
          leftLoadSharePct: 52.4,
          rightLoadSharePct: 47.6,
          meanStrideTimeMs: 1104,
        },
        {
          // rule-v1.1.0 결과: 요약 지표가 계약에 없어 모두 null(DEC-023)
          ...completed,
          sessionId: '0f0c1c0d-4b6d-4e39-9d1c-3a4d0b3e9c11',
          createdAt: '2026-08-20T07:00:00Z',
          memo: null,
          dataQualityScore: 58,
          algorithmVersion: 'rule-v1.1.0',
          dataQualityLevel: 'POOR',
          symmetryIndex: null,
          leftLoadSharePct: null,
          rightLoadSharePct: null,
          meanStrideTimeMs: null,
        },
        {
          // 아직 분석 전: 지표 줄과 품질 배지를 만들지 않는다
          ...completed,
          sessionId: '7b1b4f2a-8f0e-4c5a-9e2d-1b2c3d4e5f60',
          createdAt: '2026-08-10T07:00:00Z',
          status: 'MEASURING',
          endedAt: null,
          dataQualityScore: null,
          primaryPatternCode: null,
        },
      ],
      page: 0,
      size: 10,
      totalElements: 3,
      totalPages: 1,
    });
    renderPage();

    const links = await screen.findAllByRole('link', { name: /측정 상세 보기/ });
    const rows = links.map((link) => link.closest<HTMLElement>('[role="row"]'));
    expect(rows).toHaveLength(3);
    const [latest, legacy, measuring] = rows;
    if (!latest || !legacy || !measuring) throw new Error('세 기록 행이 렌더링되어야 합니다.');

    expect(
      within(latest).getByText('대칭 지수 12.4 · 스트라이드 1.1 s · 좌우 52:48'),
    ).toBeInTheDocument();
    expect(within(latest).getByText('91점')).toBeInTheDocument();
    expect(within(latest).getByText('좋음')).toBeInTheDocument();

    expect(within(legacy).getByText('요약 지표 제공 안 됨')).toBeInTheDocument();
    expect(within(legacy).getByText('58점')).toBeInTheDocument();
    expect(within(legacy).getByText('낮음')).toBeInTheDocument();
    expect(within(legacy).getByText('메모 없음')).toBeInTheDocument();

    expect(within(measuring).getByText('분석 전')).toBeInTheDocument();
    expect(within(measuring).queryByText(/요약 지표/)).toBeNull();
    expect(within(measuring).getByText('측정 중')).toBeInTheDocument();
    expect(within(measuring).getByRole('link', { name: /측정 상세 보기/ })).toHaveAttribute(
      'href',
      '/measurements/7b1b4f2a-8f0e-4c5a-9e2d-1b2c3d4e5f60/live',
    );

    // 진단형 표현 회귀 방지
    expect(document.body.textContent).not.toMatch(/정상|양호|개선|악화|위험|최대 압력|CoP|중등도/);
  });
});
