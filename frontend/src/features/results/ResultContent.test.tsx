import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { AnalysisResultResponse } from '../../api/types';
import { ResultContent } from './ResultContent';
import { shouldPollResult } from './resultPolling';

const result: AnalysisResultResponse = {
  sessionId: '5803f871-9fca-4a7f-a2c7-9b567a92a6cf',
  status: 'COMPLETED',
  algorithmVersion: 'rule-v1.1.0',
  dataQuality: { score: 92, level: 'GOOD', missingFrameRate: 0.003, flags: [] },
  gaitSummary: { validStepCount: 20, cadence: 108.2, leftContactTimeMs: 642, rightContactTimeMs: 608, symmetryIndex: 5.3 },
  pressureDistribution: {
    leftMedialRatio: 0.61, leftLateralRatio: 0.39, rightMedialRatio: 0.58,
    rightLateralRatio: 0.42, leftHeelRatio: 0.35, rightHeelRatio: 0.34,
    leftMidfootRatio: 0.25, rightMidfootRatio: 0.26,
    leftForefootRatio: 0.4, rightForefootRatio: 0.4,
    leftPeakPressure: 88.4, rightPeakPressure: 91.2,
    leftMeanCoP: { x: 0.42, y: 0.67 }, rightMeanCoP: null,
  },
  patterns: [],
  recommendations: [],
  disclaimer: '본 결과는 의료 진단이 아니며, 통증이 지속되면 전문가의 평가가 필요합니다.',
  createdAt: '2026-09-02T07:16:03Z',
};

describe('결과 표시와 폴링', () => {
  it('처리 중에만 폴링하고 완료 후 중단한다', () => {
    expect(shouldPollResult(undefined)).toBe(true);
    expect(shouldPollResult({ kind: 'processing', data: { sessionId: result.sessionId, status: 'PROCESSING', message: '분석 중' } })).toBe(true);
    expect(shouldPollResult({ kind: 'completed', data: result })).toBe(false);
  });

  it('패턴이 없어도 정상·질환을 확정하지 않고 서버 disclaimer를 항상 표시한다', () => {
    render(<MemoryRouter><ResultContent result={result} /></MemoryRouter>);
    expect(screen.getByText('이번 측정에서 표시할 주요 패턴이 없습니다.')).toBeInTheDocument();
    expect(screen.getByText(/질환이 없거나 완전히 정상임을 확정하는 의미가 아닙니다/)).toBeInTheDocument();
    expect(screen.getByText(result.disclaimer)).toBeInTheDocument();
    expect(screen.queryByText('평발입니다')).not.toBeInTheDocument();
    expect(screen.queryByText('치료됩니다')).not.toBeInTheDocument();
    expect(screen.getByText('분석에 사용한 유효 걸음')).toBeInTheDocument();
    expect(screen.getAllByText('중족부')).toHaveLength(2);
    expect(screen.getAllByText('최대 압력')).toHaveLength(2);
    expect(screen.getByText('x 0.42 · y 0.67')).toBeInTheDocument();
    expect(screen.getByText('데이터 없음')).toBeInTheDocument();
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
    render(<MemoryRouter><ResultContent result={legacy} /></MemoryRouter>);
    expect(screen.getByText('이전 분석')).toBeInTheDocument();
    expect(screen.getAllByText('제공 안 됨')).toHaveLength(6);
  });
});
