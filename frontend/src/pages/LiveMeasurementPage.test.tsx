import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { deviceApi, measurementApi } from '../api/services';
import type { MeasurementSessionResponse } from '../api/types';
import { PREFERENCES_STORAGE_KEY } from '../app/preferences';
import { AuthProvider } from '../features/auth/AuthContext';
import type * as RealtimeModule from '../features/realtime/useRealtimeMeasurement';
import type { RealtimeState } from '../features/realtime/useRealtimeMeasurement';
import { LiveMeasurementPage } from './LiveMeasurementPage';

// 실시간 훅은 실제 구현을 쓰되, 도넛 테스트에서만 좌우 총 신호를 덮어쓴다.
const realtimeOverride = vi.hoisted(() => ({ state: null as Partial<RealtimeState> | null }));
vi.mock('../features/realtime/useRealtimeMeasurement', async (importOriginal) => {
  const actual = await importOriginal<typeof RealtimeModule>();
  return {
    ...actual,
    useRealtimeMeasurement: (sessionId: string, enabled: boolean): RealtimeState => {
      const real = actual.useRealtimeMeasurement(sessionId, enabled);
      return realtimeOverride.state ? { ...real, ...realtimeOverride.state } : real;
    },
  };
});

const sessionId = '5803f871-9fca-4a7f-a2c7-9b567a92a6cf';
const leftDeviceId = 'b4b96290-ad73-42d9-ae21-1446f1258861';
const rightDeviceId = '64eb539f-4b48-44f6-bb30-d26861463ca6';

const createdSession: MeasurementSessionResponse = {
  sessionId,
  status: 'CREATED',
  leftDeviceId,
  rightDeviceId,
  sampleRateHz: 50,
  sourceType: 'DEVICE',
  adcMax: 4095,
  memo: null,
  createdAt: '2026-09-02T07:00:00Z',
};
const measuringSession: MeasurementSessionResponse = {
  ...createdSession,
  status: 'MEASURING',
  startedAt: '2026-09-02T07:01:00Z',
};
// 도넛 테스트용 실시간 발 데이터(센서 값은 히트맵이 아니라 총 신호만 본다).
const footData = {
  connected: true,
  lastSequence: 10,
  deviceTimeMs: 1_000,
  sensorValues: [],
  totalPressure: 0,
  cop: null,
  contactState: 'CONTACT' as const,
  lastReceivedAt: '2026-09-02T07:01:01Z',
};
const STANDING_TITLE = '정강이 움직임 기준 자세: 2초간 가만히 서 있어 주세요';
const STANDING_HINT = '정강이 IMU 기준 자세는 측정 시작 직후 정지 구간에서 자동으로 잡힙니다.';
// 의료 판단 문구 회귀 방지(frontend AGENTS.md '진단형 표현 회귀 방지').
const BANNED_WORDING = /정상|양호|개선|악화|위험|최대 압력|CoP|중등도|내번|외번|평발|족저근막염/;

const renderLive = () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
          <Routes>
            <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
};

describe('LiveMeasurementPage', () => {
  afterEach(() => {
    realtimeOverride.state = null;
    window.localStorage.removeItem(PREFERENCES_STORAGE_KEY);
    vi.useRealTimers();
  });

  it('시작 응답을 잃어도 새 세션을 만들지 않고 같은 세션 ID로 재시도한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'CREATED',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      adcMax: 4095,
      memo: null,
      createdAt: '2026-09-02T07:00:00Z',
    });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([
      {
        deviceId: leftDeviceId,
        serialNumber: 'INSOLE-L-001',
        displayName: '왼발 인솔',
        footSide: 'LEFT',
        sensorCount: 8,
        sensorLayoutVersion: 'layout-s01s08-v1',
        activeCalibrationVersion: 'identity-v1',
        firmwareVersion: '0.1.0',
        adcMax: 4095,
        status: 'ACTIVE',
        registeredAt: '2026-09-02T07:00:00Z',
      },
      {
        deviceId: rightDeviceId,
        serialNumber: 'INSOLE-R-001',
        displayName: '오른발 인솔',
        footSide: 'RIGHT',
        sensorCount: 8,
        sensorLayoutVersion: 'layout-s01s08-v1',
        activeCalibrationVersion: 'identity-v1',
        firmwareVersion: '0.1.0',
        adcMax: 4095,
        status: 'ACTIVE',
        registeredAt: '2026-09-02T07:00:00Z',
      },
    ]);
    vi.spyOn(deviceApi, 'getLayout').mockResolvedValue({
      version: 'layout-s01s08-v1',
      sensorCount: 8,
      points: Array.from({ length: 8 }, (_unused, index) => ({
        index,
        x: index / 8,
        y: index / 8,
        region: 'MIDFOOT',
        medialLateral: 'CENTER',
      })),
    });
    const start = vi
      .spyOn(measurementApi, 'start')
      .mockRejectedValueOnce(new Error('응답을 확인하지 못했습니다.'))
      .mockResolvedValue({
        sessionId,
        status: 'MEASURING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        adcMax: 4095,
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '측정 시작' }));
    await user.click(await screen.findByRole('button', { name: '측정 시작 다시 시도' }));

    await waitFor(() => expect(start).toHaveBeenCalledTimes(2));
    expect(start).toHaveBeenNthCalledWith(1, sessionId);
    expect(start).toHaveBeenNthCalledWith(2, sessionId);
  });

  it('실기기·전송률 배지, 수신기 업로드 안내, 세션 ID 복사 버튼을 제공한다', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'MEASURING',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 50,
      sourceType: 'DEVICE',
      adcMax: 4095,
      memo: null,
      startedAt: '2026-09-02T07:01:00Z',
      createdAt: '2026-09-02T07:00:00Z',
      receiverState: 'STREAMING',
      receiverPendingBatches: 2,
    });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('실기기 · 50Hz')).toBeInTheDocument();
    expect(screen.getByText('수신기 스트리밍 중 · 미전송 배치 2개')).toBeInTheDocument();
    expect(screen.queryByText(/시뮬레이션 세션입니다/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '세션 ID 복사' }));
    expect(writeText).toHaveBeenCalledWith(sessionId);
    expect(await screen.findByText('복사했습니다.')).toBeInTheDocument();
  });

  it('시뮬레이션 세션은 준비 화면에서도 세션 ID 복사와 안내를 제공한다', async () => {
    vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'CREATED',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      adcMax: 4095,
      memo: null,
      createdAt: '2026-09-02T07:00:00Z',
    });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('button', { name: '측정 시작' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '세션 ID 복사' })).toBeInTheDocument();
    expect(screen.getByText(sessionId)).toBeInTheDocument();
    expect(screen.getByText(/시뮬레이션 세션입니다/)).toBeInTheDocument();
  });

  it('기기 목록 조회 실패를 영구 로딩으로 숨기지 않고 재시도한다', async () => {
    const user = userEvent.setup();
    const measurementGet = vi.spyOn(measurementApi, 'get').mockResolvedValue({
      sessionId,
      status: 'MEASURING',
      leftDeviceId,
      rightDeviceId,
      sampleRateHz: 100,
      sourceType: 'SIMULATED',
      adcMax: 4095,
      memo: null,
      startedAt: '2026-09-02T07:01:00Z',
      createdAt: '2026-09-02T07:00:00Z',
    });
    const deviceList = vi
      .spyOn(deviceApi, 'list')
      .mockRejectedValueOnce(new Error('기기 목록을 불러오지 못했습니다.'))
      .mockResolvedValue([]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    expect(
      await screen.findByRole('heading', { name: '정보를 불러오지 못했어요' }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(deviceList).toHaveBeenCalledTimes(2));
    expect(measurementGet).toHaveBeenCalledTimes(2);
  });

  it('종료 응답을 잃어도 서버 상태를 재조회해 결과 화면으로 복구한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(measurementApi, 'get')
      .mockResolvedValueOnce({
        sessionId,
        status: 'MEASURING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        adcMax: 4095,
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      })
      .mockResolvedValue({
        sessionId,
        status: 'PROCESSING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        adcMax: 4095,
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        endedAt: '2026-09-02T07:02:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const complete = vi.spyOn(measurementApi, 'complete').mockRejectedValue(new Error('응답 유실'));
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
              <Route element={<h1>복구된 결과 화면</h1>} path="/measurements/:sessionId/result" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: /측정 종료/ }));
    expect(await screen.findByRole('heading', { name: '복구된 결과 화면' })).toBeInTheDocument();
    expect(complete).toHaveBeenCalledWith(sessionId);
  });

  it('취소 응답을 잃어도 서버 상태를 재조회해 대시보드로 복구한다', async () => {
    const user = userEvent.setup();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.spyOn(measurementApi, 'get')
      .mockResolvedValueOnce({
        sessionId,
        status: 'MEASURING',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        adcMax: 4095,
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      })
      .mockResolvedValue({
        sessionId,
        status: 'CANCELLED',
        leftDeviceId,
        rightDeviceId,
        sampleRateHz: 100,
        sourceType: 'SIMULATED',
        adcMax: 4095,
        memo: null,
        startedAt: '2026-09-02T07:01:00Z',
        endedAt: '2026-09-02T07:02:00Z',
        createdAt: '2026-09-02T07:00:00Z',
      });
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const cancel = vi.spyOn(measurementApi, 'cancel').mockRejectedValue(new Error('응답 유실'));
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });

    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <MemoryRouter initialEntries={[`/measurements/${sessionId}/live`]}>
            <Routes>
              <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
              <Route element={<h1>복구된 대시보드</h1>} path="/dashboard" />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: '측정 취소' }));
    expect(await screen.findByRole('heading', { name: '복구된 대시보드' })).toBeInTheDocument();
    expect(cancel).toHaveBeenCalledWith(sessionId);
  });

  it('시작이 성공하면 2초 정지 구간 카운트다운(2→1→완료)을 보여주고 기준 자세 안내는 계속 남는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    vi.spyOn(measurementApi, 'get')
      .mockResolvedValueOnce(createdSession)
      .mockResolvedValue(measuringSession);
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const start = vi.spyOn(measurementApi, 'start').mockResolvedValue(measuringSession);
    renderLive();

    // 준비 화면에서도 정지 프로토콜을 미리 안내한다.
    expect(await screen.findByText(STANDING_HINT, { exact: false })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: STANDING_TITLE })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '측정 시작' }));
    const countdown = await screen.findByRole('region', { name: STANDING_TITLE });
    expect(start).toHaveBeenCalledWith(sessionId);
    expect(within(countdown).getByText('2')).toBeInTheDocument();
    // 남은 시간 문장만 live region이라 틱마다 제목을 다시 읽지 않는다.
    expect(within(countdown).getByRole('status')).toHaveTextContent('남은 시간 2초');
    expect(within(countdown).getByText('STANDING REFERENCE')).toHaveAttribute(
      'aria-hidden',
      'true',
    );
    expect(countdown).not.toHaveClass('standing-countdown--static');

    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(within(countdown).getByText('1')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(within(countdown).getByText('완료')).toBeInTheDocument();
    expect(within(countdown).getByRole('status')).toHaveTextContent('기준 자세 구간이 끝났습니다');
    expect(countdown).toHaveClass('standing-countdown--done');

    act(() => {
      vi.advanceTimersByTime(1_500);
    });
    expect(screen.queryByRole('region', { name: STANDING_TITLE })).not.toBeInTheDocument();
    // 측정 중 화면에는 안내 문장이 항상 남고 종료·취소 제어도 그대로다.
    expect(screen.getByText(STANDING_HINT)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /측정 종료/ })).toHaveLength(1);
    expect(screen.getByRole('button', { name: '측정 취소' })).toBeInTheDocument();
  });

  it("'애니메이션 줄이기' 설정이면 카운트다운 링 애니메이션을 끈다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    window.localStorage.setItem(
      PREFERENCES_STORAGE_KEY,
      JSON.stringify({ defaultSampleRateHz: 50, heatmapMode: 'continuous', reduceMotion: true }),
    );
    vi.spyOn(measurementApi, 'get')
      .mockResolvedValueOnce(createdSession)
      .mockResolvedValue(measuringSession);
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    vi.spyOn(measurementApi, 'start').mockResolvedValue(measuringSession);
    renderLive();

    await user.click(await screen.findByRole('button', { name: '측정 시작' }));
    const countdown = await screen.findByRole('region', { name: STANDING_TITLE });
    expect(countdown).toHaveClass('standing-countdown--static');
    expect(countdown.querySelector('.standing-countdown__arc')).not.toHaveAttribute('style');
  });

  it('좌우 신호 비율을 도넛으로 보여주고 의료 판단이 아님을 밝힌다', async () => {
    realtimeOverride.state = {
      connectionStatus: 'CONNECTED',
      left: { ...footData, totalPressure: 52 },
      right: { ...footData, totalPressure: 48 },
      leftPeak: 71.4,
      rightPeak: 66.2,
    };
    vi.spyOn(measurementApi, 'get').mockResolvedValue(measuringSession);
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    const { container } = renderLive();

    expect(await screen.findByRole('img', { name: '왼발 52%, 오른발 48%' })).toBeInTheDocument();
    expect(screen.getByText('52 : 48')).toBeInTheDocument();
    expect(
      screen.getByText('한 시점의 상대 총 신호 비교이며 의료적 판단 기준이 아닙니다.'),
    ).toBeInTheDocument();
    expect(container.querySelector('.balance-meter')).toBeNull();
    expect(screen.getByText('연결 연결됨')).toBeInTheDocument();
    expect(screen.getByText('품질 확인 중')).toBeInTheDocument();
    // 연결 상태·품질 단계만 낭독하고(점수 제외) 배지 묶음 자체는 live region이 아니다.
    expect(screen.getByText('연결 연결됨 · 품질 확인 중')).toHaveAttribute('role', 'status');
    expect(container.querySelector('.live-status-strip')).not.toHaveAttribute('aria-live');
    expect(screen.getByRole('region', { name: '측정 제어' })).toBeInTheDocument();
    expect(screen.getByText('실기기 · 50Hz')).toBeInTheDocument();
    expect(screen.getByLabelText('상대 신호 범례: 0은 낮음, 100은 높음')).toHaveClass(
      'pressure-legend--vertical',
    );
    expect(screen.getByText('71')).toBeInTheDocument();
    expect(screen.getByText('66')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(BANNED_WORDING);
  });

  it('실시간 데이터가 없으면 도넛을 비우고 계산 불가를 알린다', async () => {
    vi.spyOn(measurementApi, 'get').mockResolvedValue(measuringSession);
    vi.spyOn(deviceApi, 'list').mockResolvedValue([]);
    renderLive();

    expect(
      await screen.findByRole('img', { name: '아직 좌우 신호 비율을 계산할 수 없습니다.' }),
    ).toBeInTheDocument();
    // 도넛 가운데 + 세션 최대 센서 신호 L/R (히트맵의 빈 값 표시는 제외)
    const balance = screen.getByRole('region', { name: '현재 좌우 신호 비율' });
    expect(within(balance).getAllByText('—')).toHaveLength(3);
    expect(within(balance).getAllByText('제공 안 됨')).toHaveLength(2);
  });
});
