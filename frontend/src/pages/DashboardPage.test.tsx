import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { deviceApi, measurementApi } from '../api/services';
import type {
  AnalysisResultResponse,
  DeviceResponse,
  MeasurementHistoryItem,
  MeasurementSessionPage,
  SensorLayoutResponse,
} from '../api/types';
import { AuthProvider } from '../features/auth/AuthContext';
import { movementTerms, OBSERVATION_LEVEL_UNAVAILABLE } from '../utils/labels';
import { DashboardPage } from './DashboardPage';

// 판정 어휘 회귀 방지(charts/wording.test와 같은 목록). MovementCard의 면책 문구가 말하는
// '내번·외번이 아니다'는 부정문이라 목록에 넣지 않는다.
const banned = /정상|양호|개선|악화|위험|최대 압력|CoP|중등도|압력 중심|총압력|평발|족저근막염/;

const NOW = new Date('2026-09-11T09:00:00Z');
const FROM = '2026-09-04T09:00:00.000Z';

const seedSession = () => {
  window.sessionStorage.setItem(
    'smart-insole.auth.v1',
    JSON.stringify({
      accessToken: 'token',
      expiresAt: Date.now() + 3_600_000,
      user: {
        userId: 'a7d3e6b8-6a37-4a5e-9a0f-0a6b4c2e1d11',
        email: 'walker@example.com',
        name: '홍길동',
        createdAt: '2026-08-01T00:00:00Z',
      },
    }),
  );
};

const layout: SensorLayoutResponse = {
  version: 'layout-s01s08-v1',
  sensorCount: 8,
  points: [
    { label: 'S01', index: 0, x: 0.4, y: 0.88, region: 'HEEL', medialLateral: 'MEDIAL' },
    { label: 'S02', index: 1, x: 0.62, y: 0.88, region: 'HEEL', medialLateral: 'LATERAL' },
    { label: 'S03', index: 2, x: 0.36, y: 0.62, region: 'MIDFOOT', medialLateral: 'MEDIAL' },
    { label: 'S04', index: 3, x: 0.66, y: 0.62, region: 'MIDFOOT', medialLateral: 'LATERAL' },
    { label: 'S05', index: 4, x: 0.32, y: 0.36, region: 'FOREFOOT', medialLateral: 'MEDIAL' },
    { label: 'S06', index: 5, x: 0.5, y: 0.34, region: 'FOREFOOT', medialLateral: 'CENTER' },
    { label: 'S07', index: 6, x: 0.7, y: 0.38, region: 'FOREFOOT', medialLateral: 'LATERAL' },
    { label: 'S08', index: 7, x: 0.36, y: 0.12, region: 'TOE', medialLateral: 'MEDIAL' },
  ],
};

const LEFT_DEVICE_ID = 'b4b96290-ad73-42d9-ae21-1446f1258861';
const RIGHT_DEVICE_ID = '64eb539f-4b48-44f6-bb30-d26861463ca6';

const devices: DeviceResponse[] = [
  {
    deviceId: LEFT_DEVICE_ID,
    serialNumber: 'SI-L-001',
    displayName: '왼발 인솔',
    footSide: 'LEFT',
    sensorCount: 8,
    sensorLayoutVersion: layout.version,
    firmwareVersion: '1.0.0',
    adcMax: 4095,
    activeCalibrationVersion: null,
    status: 'ACTIVE',
    registeredAt: '2026-08-01T00:00:00Z',
  },
  {
    deviceId: RIGHT_DEVICE_ID,
    serialNumber: 'SI-R-001',
    displayName: '오른발 인솔',
    footSide: 'RIGHT',
    sensorCount: 8,
    sensorLayoutVersion: layout.version,
    firmwareVersion: '1.0.0',
    adcMax: 4095,
    activeCalibrationVersion: null,
    status: 'ACTIVE',
    registeredAt: '2026-08-01T00:00:00Z',
  },
];

const latestSession: MeasurementHistoryItem = {
  sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
  status: 'COMPLETED',
  leftDeviceId: LEFT_DEVICE_ID,
  rightDeviceId: RIGHT_DEVICE_ID,
  sampleRateHz: 50,
  sourceType: 'DEVICE',
  createdAt: '2026-09-11T07:00:00Z',
  primaryPatternCode: 'MEDIAL_LOAD_TENDENCY',
  dataQualityScore: 92,
  algorithmVersion: 'rule-v1.4.0',
  dataQualityLevel: 'GOOD',
  symmetryIndex: 5.3,
  cadence: 108.2,
  leftContactTimeMs: 642,
  rightContactTimeMs: 608,
  validStepCount: 20,
  leftLoadSharePct: 52,
  rightLoadSharePct: 48,
  meanStrideTimeMs: 1120,
};

const olderSessions: MeasurementHistoryItem[] = [
  {
    ...latestSession,
    sessionId: '1b2c3d4e-0000-4000-8000-000000000002',
    createdAt: '2026-09-08T07:00:00Z',
    dataQualityScore: 90,
    symmetryIndex: 7.1,
    leftLoadSharePct: 50,
    rightLoadSharePct: 50,
    meanStrideTimeMs: 1100,
  },
  {
    ...latestSession,
    sessionId: '1b2c3d4e-0000-4000-8000-000000000003',
    createdAt: '2026-09-05T07:00:00Z',
    dataQualityScore: 80,
    algorithmVersion: 'rule-v1.1.0',
    symmetryIndex: 9.4,
    leftLoadSharePct: null,
    rightLoadSharePct: null,
    meanStrideTimeMs: null,
  },
];

const page = (items: MeasurementHistoryItem[], size: number): MeasurementSessionPage => ({
  items,
  page: 0,
  size,
  totalElements: items.length,
  totalPages: items.length === 0 ? 0 : 1,
});

const fullResult: AnalysisResultResponse = {
  sessionId: latestSession.sessionId,
  status: 'COMPLETED',
  algorithmVersion: 'rule-v1.4.0',
  dataQuality: { score: 92, level: 'GOOD', missingFrameRate: 0.003, flags: [] },
  gaitSummary: {
    validStepCount: 20,
    cadence: 108.2,
    leftContactTimeMs: 642,
    rightContactTimeMs: 608,
    symmetryIndex: 5.3,
    leftStrideTimeMs: 1130,
    rightStrideTimeMs: 1110,
    meanStrideTimeMs: 1120,
  },
  pressureDistribution: {
    leftMedialRatio: 0.61,
    leftLateralRatio: 0.39,
    rightMedialRatio: 0.58,
    rightLateralRatio: 0.42,
    leftHeelRatio: 0.35,
    rightHeelRatio: 0.34,
    leftMidfootRatio: 0.25,
    rightMidfootRatio: 0.26,
    leftForefootRatio: 0.4,
    rightForefootRatio: 0.4,
    leftPeakPressure: 88.4,
    rightPeakPressure: 91.2,
    leftMeanCoP: { x: 0.42, y: 0.67 },
    rightMeanCoP: { x: 0.5, y: 0.6 },
    leftSensorSharePct: [18, 12, 8, 7, 20, 15, 10, 10],
    rightSensorSharePct: [30, 20, 5, 5, 15, 10, 10, 5],
    leftLoadSharePct: 52,
    rightLoadSharePct: 48,
  },
  patterns: [
    {
      code: 'MEDIAL_LOAD_TENDENCY',
      severity: 'CAUTION',
      title: '내측 하중 경향',
      message: '내측 센서 신호가 반복적으로 높게 관찰되었습니다.',
      evidence: '내측 비율 0.61',
      observationLevel: 'REPEATEDLY_OBSERVED',
      occurrenceRate: 0.7,
      observedCount: 14,
      windowCount: 20,
    },
    {
      code: 'LEFT_RIGHT_ASYMMETRY',
      severity: 'INFO',
      title: '좌우 비대칭 경향',
      message: '좌우 접촉 시간 차이가 일부 걸음 쌍에서 관찰되었습니다.',
      evidence: '대칭 지수 5.3',
      observationLevel: 'PARTIALLY_OBSERVED',
      occurrenceRate: 0.25,
      observedCount: 5,
      windowCount: 20,
    },
  ],
  observationSummary: [
    {
      code: 'LEFT_RIGHT_ASYMMETRY',
      observationLevel: 'PARTIALLY_OBSERVED',
      occurrenceRate: 0.25,
      observedCount: 5,
      windowCount: 20,
    },
    {
      code: 'MEDIAL_LOAD_TENDENCY',
      observationLevel: 'REPEATEDLY_OBSERVED',
      occurrenceRate: 0.7,
      observedCount: 14,
      windowCount: 20,
    },
  ],
  movementSummary: {
    imuCoverage: 0.92,
    referenceMethod: 'QUIET_STANDING',
    left: {
      frontalTiltDeg: 3.2,
      sagittalRangeDeg: 41.7,
      transverseRangeDeg: 12.5,
      swingPeakAngularVelocityDps: 318.4,
      windowCount: 18,
    },
    right: null,
  },
  recommendations: [
    {
      code: 'BALANCED_FOOT_LOADING',
      title: '양발 균등 하중 연습',
      summary: '양발에 고르게 서는 연습입니다.',
      durationMinutes: 8,
    },
    {
      code: 'ANKLE_MOBILITY',
      title: '발목 가동 연습',
      summary: '발목을 천천히 움직이는 연습입니다.',
      durationMinutes: 6,
    },
  ],
  disclaimer: '본 결과는 의료 진단이 아니며, 통증이 지속되면 전문가의 평가가 필요합니다.',
  createdAt: '2026-09-11T07:16:03Z',
};

const legacyResult: AnalysisResultResponse = {
  ...fullResult,
  algorithmVersion: 'rule-v1.1.0',
  dataQuality: { score: 74, level: 'ACCEPTABLE', missingFrameRate: 0.08, flags: ['SEQUENCE_GAP'] },
  gaitSummary: {
    validStepCount: null,
    cadence: 100,
    leftContactTimeMs: 600,
    rightContactTimeMs: 620,
    symmetryIndex: 3.3,
  },
  pressureDistribution: {
    ...fullResult.pressureDistribution,
    leftMidfootRatio: null,
    rightMidfootRatio: null,
    leftForefootRatio: null,
    rightForefootRatio: null,
    leftSensorSharePct: null,
    rightSensorSharePct: null,
    leftLoadSharePct: null,
    rightLoadSharePct: null,
  },
  patterns: [
    {
      code: 'HIGH_MIDFOOT_LOAD',
      severity: 'INFO',
      title: '중족부 하중 증가 경향',
      message: '중족부 센서 신호가 높게 관찰되었습니다.',
      evidence: '중족부 비율',
      observationLevel: null,
    },
  ],
  observationSummary: null,
  movementSummary: null,
  recommendations: [],
};

const mockApi = ({
  latest,
  history,
  result,
  deviceList = devices,
}: {
  latest: MeasurementHistoryItem | null;
  history: MeasurementHistoryItem[];
  result: AnalysisResultResponse | null;
  deviceList?: DeviceResponse[];
}) => {
  const list = vi
    .spyOn(measurementApi, 'list')
    .mockImplementation((params) =>
      Promise.resolve(
        params.size === 1 ? page(latest ? [latest] : [], 1) : page(history, params.size),
      ),
    );
  const resultSpy = vi
    .spyOn(measurementApi, 'result')
    .mockImplementation(() =>
      result
        ? Promise.resolve({ kind: 'completed', data: result })
        : Promise.reject(new Error('결과 없음')),
    );
  vi.spyOn(deviceApi, 'list').mockResolvedValue(deviceList);
  vi.spyOn(deviceApi, 'getLayout').mockResolvedValue(layout);
  return { list, resultSpy };
};

const renderDashboard = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={['/dashboard']}>
          <DashboardPage now={NOW} />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
};

const kpiByLabel = (label: string): HTMLElement => {
  const labelNode = screen.getByText(label, { selector: '.kpi-card__label' });
  const card = labelNode.closest('.kpi-card');
  if (!(card instanceof HTMLElement)) throw new Error(`KPI 카드 없음: ${label}`);
  return card;
};

describe('DashboardPage', () => {
  beforeEach(() => {
    seedSession();
  });

  it('최신 결과와 최근 7일 요약으로 KPI·차트·추천을 그린다', async () => {
    const { list } = mockApi({
      latest: latestSession,
      history: [latestSession, ...olderSessions],
      result: fullResult,
    });
    const { container } = renderDashboard();

    expect(
      screen.getByRole('heading', { level: 1, name: '홍길동님의 걸음을 살펴볼까요?' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '새 측정' })).toHaveAttribute(
      'href',
      '/measurements/new',
    );

    // KPI
    const loadShare = await screen.findByText('좌우 신호 비율', {
      selector: '.kpi-card__label',
    });
    expect(loadShare).toBeInTheDocument();
    expect(within(kpiByLabel('좌우 신호 비율')).getByText('52 : 48')).toBeInTheDocument();
    const symmetry = kpiByLabel('좌우 대칭 지수');
    expect(within(symmetry).getByText('5.3')).toBeInTheDocument();
    expect(within(symmetry).getByText('접촉 시간 차이 %')).toBeInTheDocument();
    expect(within(symmetry).getByText('일부 관찰')).toBeInTheDocument();
    const patterns = kpiByLabel('관찰된 패턴');
    expect(within(patterns).getByText('2')).toBeInTheDocument();
    expect(within(patterns).getByText('반복 관찰 1 · 일부 관찰 1')).toBeInTheDocument();
    const quality = kpiByLabel('데이터 품질');
    expect(within(quality).getByText('92')).toBeInTheDocument();
    expect(within(quality).getByText('좋음')).toBeInTheDocument();
    // 최신 92 vs 다른 두 세션 평균 85 → +7점 증가('개선' 금지)
    expect(within(quality).getByText('최근 7일 평균 대비 증가 (+7점)')).toBeInTheDocument();

    // 7일 창 조회 파라미터
    expect(list).toHaveBeenCalledWith({ page: 0, size: 1, status: 'COMPLETED' });
    expect(list).toHaveBeenCalledWith({ page: 0, size: 10, status: 'COMPLETED', from: FROM });

    // 세션 평균 히트맵 2개 + 세로 범례
    expect(
      await screen.findByRole('img', { name: '왼발 센서 신호 비율 히트맵' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '오른발 센서 신호 비율 히트맵' })).toBeInTheDocument();
    expect(container.querySelector('.pressure-legend--vertical')).not.toBeNull();
    expect(screen.getByText('센서 신호 비율 (%)')).toBeInTheDocument();

    // 부위별 분포
    const region = screen.getByRole('group', { name: '부위별 센서 신호 비율' });
    expect(within(region).getByText('후족부')).toBeInTheDocument();
    expect(within(region).getByText('중족부')).toBeInTheDocument();
    expect(within(region).getByText('전족부·발가락')).toBeInTheDocument();

    // 보행 분석
    expect(screen.getByRole('group', { name: '좌우 접촉 시간' })).toBeInTheDocument();
    expect(screen.getByText('분당 접촉 횟수')).toBeInTheDocument();
    expect(screen.getByText('108.2')).toBeInTheDocument();
    expect(screen.getByText('스트라이드 시간(추정)', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.getByText('1,120')).toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: '최근 7일 스트라이드 시간(추정) 흐름' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: '좌우 신호 비율 왼발 52%, 오른발 48%' }),
    ).toBeInTheDocument();

    // 정강이 움직임(기능 검증용) + 관찰된 패턴
    expect(screen.getByRole('heading', { name: movementTerms.cardTitle })).toBeInTheDocument();
    const patternList = screen.getByRole('list', { name: '관찰된 패턴' });
    expect(within(patternList).getAllByRole('listitem')).toHaveLength(2);
    expect(within(patternList).getByText('내측 하중 경향')).toBeInTheDocument();
    expect(within(patternList).getByText('발생 비율 70% (14/20 걸음)')).toBeInTheDocument();

    // 추천 운동 + CTA
    expect(screen.getAllByRole('link', { name: '가이드 확인' })).toHaveLength(2);
    expect(screen.getByRole('link', { name: '결과와 연결된 운동 가이드 보기' })).toHaveAttribute(
      'href',
      '/recommendations',
    );
    expect(screen.getByRole('link', { name: '전체 결과 보기' })).toHaveAttribute(
      'href',
      `/measurements/${latestSession.sessionId}/result`,
    );

    expect(screen.queryByRole('status')).toBeNull();
    expect(container.textContent).not.toMatch(banned);
  });

  it('추세 지표를 바꾸면 차트 요약·단위가 바뀌고 x축 라벨은 날짜다', async () => {
    mockApi({
      latest: latestSession,
      history: [latestSession, ...olderSessions],
      result: fullResult,
    });
    renderDashboard();
    const user = userEvent.setup();

    expect(
      await screen.findByRole('img', { name: '최근 7일 완료 측정 3회의 좌우 대칭 지수' }),
    ).toBeInTheDocument();
    expect(screen.getByText('단위: 지수')).toBeInTheDocument();

    const select = screen.getByRole('combobox', { name: '지표' });
    expect(
      within(select)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['좌우 대칭 지수', '품질 점수', '좌우 신호 비율(왼발 %)', '스트라이드 시간(추정)']);

    await user.selectOptions(select, 'meanStrideTimeMs');
    expect(
      screen.getByRole('img', { name: '최근 7일 완료 측정 3회의 스트라이드 시간(추정)' }),
    ).toBeInTheDocument();
    expect(screen.getByText('단위: 밀리초')).toBeInTheDocument();
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows.map((row) => within(row).getByRole('rowheader').textContent)).toEqual([
      '9/5',
      '9/8',
      '9/11',
    ]);
    expect(rows.map((row) => within(row).getByRole('cell').textContent)).toEqual([
      '제공 안 됨',
      '1,100',
      '1,120',
    ]);
  });

  it('이전 분석 결과(null 필드)와 빈 기록에서는 제공 안 됨과 안내 문구를 보여준다', async () => {
    mockApi({ latest: latestSession, history: [], result: legacyResult });
    const { container } = renderDashboard();

    const loadShare = await screen.findByText('좌우 신호 비율', {
      selector: '.kpi-card__label',
    });
    expect(within(kpiByLabel('좌우 신호 비율')).getByText('제공 안 됨')).toBeInTheDocument();
    expect(loadShare).toBeInTheDocument();
    const symmetry = kpiByLabel('좌우 대칭 지수');
    expect(within(symmetry).getByText('3.3')).toBeInTheDocument();
    expect(
      within(symmetry).getByText(`접촉 시간 차이 % · ${OBSERVATION_LEVEL_UNAVAILABLE}`),
    ).toBeInTheDocument();
    expect(within(symmetry).queryByText('일부 관찰')).toBeNull();
    expect(
      within(kpiByLabel('관찰된 패턴')).getByText(OBSERVATION_LEVEL_UNAVAILABLE),
    ).toBeInTheDocument();
    const quality = kpiByLabel('데이터 품질');
    expect(within(quality).getByText('74')).toBeInTheDocument();
    expect(within(quality).getByText('확인 필요')).toBeInTheDocument();
    expect(
      within(quality).getByText('최근 7일에 비교할 다른 측정이 없습니다.'),
    ).toBeInTheDocument();

    await waitFor(() =>
      expect(
        screen.getAllByText('이전 분석 결과에는 센서 비율이 없습니다.').length,
      ).toBeGreaterThan(0),
    );
    const region = screen.getByRole('group', { name: '부위별 센서 신호 비율' });
    expect(within(region).getAllByText('제공 안 됨')).toHaveLength(4);
    expect(screen.getByText('스트라이드 시간(추정)', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '좌우 신호 비율 제공 안 됨' })).toBeInTheDocument();
    expect(
      screen.getByText('이 세션에는 IMU 데이터가 없어 움직임 분석을 제공하지 않습니다.'),
    ).toBeInTheDocument();
    expect(screen.getByText('중족부 하중 증가 경향')).toBeInTheDocument();
    expect(screen.getByText('최근 7일에 표시할 좌우 대칭 지수 값이 없습니다.')).toBeInTheDocument();
    expect(screen.getByText('이번 결과에 연결된 운동 가이드가 없습니다.')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '결과와 연결된 운동 가이드 보기' }),
    ).toBeInTheDocument();
    expect(container.textContent).not.toMatch(banned);
  });

  it('완료된 측정이 없으면 첫 측정 히어로와 인솔 등록 안내를 보여준다', async () => {
    const { resultSpy } = mockApi({ latest: null, history: [], result: null, deviceList: [] });
    const { container } = renderDashboard();

    expect(await screen.findByRole('link', { name: '첫 측정 시작' })).toHaveAttribute(
      'href',
      '/measurements/new',
    );
    expect(screen.getByRole('heading', { name: '아직 완료된 측정이 없어요.' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: '인솔 등록' })).toHaveAttribute(
      'href',
      '/devices',
    );
    expect(screen.getByRole('link', { name: '새 측정' })).toBeInTheDocument();
    expect(resultSpy).not.toHaveBeenCalled();
    expect(container.querySelector('.kpi-grid')).toBeNull();
    expect(container.textContent).not.toMatch(banned);
  });

  it('시뮬레이션 세션이면 안내를 표시한다', async () => {
    mockApi({
      latest: { ...latestSession, sourceType: 'SIMULATED', sampleRateHz: 100 },
      history: [latestSession],
      result: fullResult,
    });
    renderDashboard();

    const notice = await screen.findByText(/시뮬레이션 세션의 결과입니다\./);
    expect(notice).toHaveAttribute('role', 'status');
    // 결과 페이지처럼 SIMULATED 안내 하나만 role=status다(스피너는 사라진 뒤).
    await waitFor(() => expect(screen.getAllByRole('status')).toHaveLength(1));
  });

  it('목록 조회가 실패하면 오류 패널과 다시 시도를 보여준다', async () => {
    vi.spyOn(measurementApi, 'list').mockRejectedValue(new Error('목록을 불러오지 못했습니다.'));
    vi.spyOn(deviceApi, 'list').mockResolvedValue(devices);
    renderDashboard();

    expect(await screen.findByText('목록을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '다시 시도' }).length).toBeGreaterThan(0);
  });
});
