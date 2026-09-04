import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { AnalysisResultResponse } from '../../api/types';
import { resultTerms } from '../../utils/labels';
import { ResultContent } from './ResultContent';
import { shouldPollResult } from './resultPolling';

const result: AnalysisResultResponse = {
  sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
  status: 'COMPLETED',
  algorithmVersion: 'rule-v1.1.0',
  dataQuality: { score: 92, level: 'GOOD', missingFrameRate: 0.003, flags: [] },
  gaitSummary: {
    validStepCount: 20,
    cadence: 108.2,
    leftContactTimeMs: 642,
    rightContactTimeMs: 608,
    symmetryIndex: 5.3,
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
    rightMeanCoP: null,
  },
  patterns: [],
  recommendations: [],
  disclaimer: '본 결과는 의료 진단이 아니며, 통증이 지속되면 전문가의 평가가 필요합니다.',
  createdAt: '2026-09-02T07:16:03Z',
};

describe('결과 표시와 폴링', () => {
  it('처리 중에만 폴링하고 완료 후 중단한다', () => {
    expect(shouldPollResult(undefined)).toBe(true);
    expect(
      shouldPollResult({
        kind: 'processing',
        data: { sessionId: result.sessionId, status: 'PROCESSING', message: '분석 중' },
      }),
    ).toBe(true);
    expect(shouldPollResult({ kind: 'completed', data: result })).toBe(false);
  });

  it('패턴이 없어도 건강 상태·질환을 확정하지 않고 서버 disclaimer를 항상 표시한다', () => {
    render(
      <MemoryRouter>
        <ResultContent result={result} />
      </MemoryRouter>,
    );
    expect(screen.getByText('이번 측정에서 표시할 주요 패턴이 없습니다.')).toBeInTheDocument();
    expect(
      screen.getByText(/질환 유무나 건강 상태를 확정하는 의미가 아닙니다/),
    ).toBeInTheDocument();
    expect(screen.getByText(result.disclaimer)).toBeInTheDocument();
    expect(screen.queryByText('평발입니다')).not.toBeInTheDocument();
    expect(screen.queryByText('치료됩니다')).not.toBeInTheDocument();
    expect(screen.getByText('분석에 사용한 유효 걸음')).toBeInTheDocument();
    expect(screen.getAllByText('중족부')).toHaveLength(2);
    expect(screen.getAllByText(resultTerms.peakSignal)).toHaveLength(2);
    expect(screen.getAllByText(`평균 ${resultTerms.estimatedCop}`)).toHaveLength(2);
    expect(screen.getByText('x 0.42 · y 0.67')).toBeInTheDocument();
    expect(screen.getByText('데이터 없음')).toBeInTheDocument();
  });

  it.each([
    ['패턴 없음', result],
    [
      '패턴 있음',
      {
        ...result,
        patterns: [
          {
            code: 'MEDIAL_LOAD_TENDENCY',
            severity: 'CAUTION' as const,
            title: '내측 하중 경향',
            message: '내측 센서 신호 비율이 높게 관찰되었습니다.',
            evidence: '내측 신호 비율 0.61',
          },
        ],
      },
    ],
  ])("결과 화면(%s)에 '최대 압력'·'CoP'·'정상' 문구를 렌더링하지 않는다", (_case, fixture) => {
    const { container } = render(
      <MemoryRouter>
        <ResultContent result={fixture} />
      </MemoryRouter>,
    );
    const text = container.textContent;
    expect(text).not.toMatch(/최대 압력/);
    expect(text).not.toMatch(/CoP/);
    expect(text).not.toMatch(/정상/);
    expect(text).toContain(resultTerms.peakSignal);
    expect(text).toContain(resultTerms.estimatedCop);
  });

  it('세션 정보가 있으면 sourceType·sampleRateHz 배지를 표시하고 시뮬레이션 결과를 안내한다', () => {
    const device = render(
      <MemoryRouter>
        <ResultContent result={result} session={{ sourceType: 'DEVICE', sampleRateHz: 50 }} />
      </MemoryRouter>,
    );
    expect(screen.getByText(/실기기 · 50Hz/)).toBeInTheDocument();
    expect(screen.queryByText(/시뮬레이션 세션의 결과입니다/)).not.toBeInTheDocument();
    device.unmount();

    render(
      <MemoryRouter>
        <ResultContent result={result} session={{ sourceType: 'SIMULATED', sampleRateHz: 100 }} />
      </MemoryRouter>,
    );
    expect(screen.getByText(/시뮬레이션 · 100Hz/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('시뮬레이션 세션의 결과입니다.');
  });

  it('rule-v1.2.0 결과는 관찰 단계 배지·발생 비율·단계별 요약·센서 share를 표시한다', () => {
    const observed: AnalysisResultResponse = {
      ...result,
      algorithmVersion: 'rule-v1.2.0',
      pressureDistribution: {
        ...result.pressureDistribution,
        leftSensorSharePct: [10, 10, 5, 5, 20, 20, 15, 15],
        rightSensorSharePct: null,
      },
      patterns: [
        {
          code: 'MEDIAL_LOAD_TENDENCY',
          severity: 'CAUTION',
          title: '내측 하중 경향',
          message: '내측 센서 신호 비율이 반복해서 높게 관찰되었습니다.',
          evidence: '내측 신호 비율 0.63',
          observationLevel: 'REPEATEDLY_OBSERVED',
          occurrenceRate: 0.619,
          observedCount: 13,
          windowCount: 21,
        },
        {
          code: 'LEFT_RIGHT_ASYMMETRY',
          severity: 'INFO',
          title: '좌우 비대칭 경향',
          message: '좌우 접촉 시간 차이가 일부 걸음 쌍에서 관찰되었습니다.',
          evidence: '차이 12%',
          observationLevel: 'PARTIALLY_OBSERVED',
          occurrenceRate: 0.25,
          observedCount: 5,
          windowCount: 20,
        },
      ],
      observationSummary: [
        {
          code: 'MEDIAL_LOAD_TENDENCY',
          observationLevel: 'REPEATEDLY_OBSERVED',
          occurrenceRate: 0.619,
          observedCount: 13,
          windowCount: 21,
        },
        {
          code: 'LATERAL_LOAD_TENDENCY',
          observationLevel: 'NOT_OBSERVED',
          occurrenceRate: 0,
          observedCount: 0,
          windowCount: 21,
        },
        {
          code: 'LEFT_RIGHT_ASYMMETRY',
          observationLevel: 'PARTIALLY_OBSERVED',
          occurrenceRate: 0.25,
          observedCount: 5,
          windowCount: 20,
        },
        {
          code: 'LOW_HALLUX_SIGNAL',
          observationLevel: 'NOT_OBSERVED',
          occurrenceRate: 0.05,
          observedCount: 1,
          windowCount: 21,
        },
        {
          code: 'FOREFOOT_LOAD_TENDENCY',
          observationLevel: 'NOT_OBSERVED',
          occurrenceRate: 0.1,
          observedCount: 2,
          windowCount: 21,
        },
        {
          code: 'REARFOOT_LOAD_TENDENCY',
          observationLevel: 'NOT_OBSERVED',
          occurrenceRate: 0.14,
          observedCount: 3,
          windowCount: 21,
        },
      ],
    };
    const { container } = render(
      <MemoryRouter>
        <ResultContent result={observed} />
      </MemoryRouter>,
    );

    const cards = container.querySelectorAll('.pattern-card');
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('반복 관찰');
    expect(cards[0]).toHaveTextContent('발생 비율 62% (13/21 걸음)');
    expect(cards[1]).toHaveTextContent('일부 관찰');
    expect(cards[1]).toHaveTextContent('발생 비율 25% (5/20 걸음 쌍)');

    const groups = container.querySelectorAll('.observation-group');
    expect(groups).toHaveLength(3);
    expect(groups[0]).toHaveTextContent('반복 관찰');
    expect(groups[0]).toHaveTextContent('1종');
    expect(groups[1]).toHaveTextContent('일부 관찰');
    expect(groups[2]).toHaveTextContent('관찰되지 않음');
    expect(groups[2]).toHaveTextContent('4종');
    expect(groups[2]).toHaveTextContent('엄지 신호 낮음');
    expect(screen.queryByText(/관찰 단계 미제공/)).not.toBeInTheDocument();

    expect(container.querySelectorAll('.share-bar')).toHaveLength(8);
    expect(container.textContent).not.toMatch(/최대 압력|CoP|정상/);
  });

  it('rule-v1.2.0 이전 결과에는 관찰 단계 미제공 안내를 표시한다', () => {
    const legacyPattern: AnalysisResultResponse = {
      ...result,
      patterns: [
        {
          code: 'MEDIAL_LOAD_TENDENCY',
          severity: 'CAUTION',
          title: '내측 하중 경향',
          message: '내측 신호가 높게 관찰되었습니다.',
          evidence: '내측 비율 0.61',
          observationLevel: null,
          occurrenceRate: null,
          observedCount: null,
          windowCount: null,
        },
      ],
      observationSummary: null,
    };
    const { container } = render(
      <MemoryRouter>
        <ResultContent result={legacyPattern} />
      </MemoryRouter>,
    );
    expect(screen.getAllByText(/관찰 단계 미제공\(이전 분석\)/)).toHaveLength(2);
    expect(container.querySelectorAll('.observation-group')).toHaveLength(0);
    expect(container.querySelectorAll('.share-bar')).toHaveLength(0);
  });

  it('이전 알고리즘에 없던 지표를 실제 0으로 오해시키지 않는다', () => {
    const legacy: AnalysisResultResponse = {
      ...result,
      algorithmVersion: 'rule-v1.0.0',
      gaitSummary: { ...result.gaitSummary, validStepCount: null },
      pressureDistribution: {
        ...result.pressureDistribution,
        leftMidfootRatio: null,
        rightMidfootRatio: null,
        leftForefootRatio: null,
        rightForefootRatio: null,
        leftPeakPressure: null,
        rightPeakPressure: null,
        leftMeanCoP: null,
        rightMeanCoP: null,
      },
    };
    render(
      <MemoryRouter>
        <ResultContent result={legacy} />
      </MemoryRouter>,
    );
    expect(screen.getByText('이전 분석')).toBeInTheDocument();
    expect(screen.getAllByText('제공 안 됨')).toHaveLength(6);
  });
});
