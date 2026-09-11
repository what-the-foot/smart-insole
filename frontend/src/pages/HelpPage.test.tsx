import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MOVEMENT_DISCLAIMER, movementTerms } from '../utils/labels';
import { HelpPage } from './HelpPage';

describe('HelpPage', () => {
  it('측정 절차와 용어집을 기존 라벨 문구로 표시한다', () => {
    const { container } = render(
      <MemoryRouter>
        <HelpPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '측정 절차' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '데이터 품질 플래그 용어집' })).toBeInTheDocument();
    // qualityFlagLabel / patternCodeLabel 문구를 그대로 사용한다.
    expect(screen.getByText('오른발 인솔의 데이터가 수신되지 않습니다.')).toBeInTheDocument();
    expect(screen.getByText('내측 하중 경향')).toBeInTheDocument();
    expect(screen.getByText('좌우 비대칭 경향')).toBeInTheDocument();
    expect(screen.getByText('최대 센서 신호')).toBeInTheDocument();
    expect(screen.getByText('추정 압력중심')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '의료 안내' })).toBeInTheDocument();
    // 진단형 표현 회귀 방지
    expect(container.textContent).not.toMatch(/정상|CoP|최대 압력|평발|족저근막염/);
  });

  it('움직임 분석(정강이 IMU) 용어를 movementTerms와 labels.ts의 안내 문장으로 표시한다', () => {
    render(
      <MemoryRouter>
        <HelpPage />
      </MemoryRouter>,
    );

    const heading = screen.getByRole('heading', { name: movementTerms.cardTitle });
    const section = heading.closest('section');
    if (!section) throw new Error('움직임 분석 섹션이 있어야 합니다.');
    expect(within(section).getByText(movementTerms.badge)).toBeInTheDocument();
    expect(within(section).getByText(movementTerms.frontalTilt)).toBeInTheDocument();
    expect(within(section).getByText(movementTerms.sagittalRange)).toBeInTheDocument();
    expect(within(section).getByText(movementTerms.transverseRange)).toBeInTheDocument();
    expect(within(section).getByText(movementTerms.swingPeakAngularVelocity)).toBeInTheDocument();
    expect(within(section).getByText(movementTerms.windowCount)).toBeInTheDocument();
    expect(within(section).getByText(movementTerms.imuCoverage)).toBeInTheDocument();
    expect(within(section).getByText(movementTerms.referenceMethod)).toBeInTheDocument();
    expect(within(section).getByText(MOVEMENT_DISCLAIMER)).toBeInTheDocument();
    // 목차에서 바로 이동할 수 있다.
    expect(screen.getByRole('link', { name: '움직임 분석' })).toHaveAttribute(
      'href',
      '#help-movement',
    );
    // 정강이 IMU 값에 참고 범위·판정 문구를 붙이지 않는다.
    expect(section.textContent).not.toMatch(/정상|양호|개선|악화|위험|중등도|참고 범위/);
  });
});
