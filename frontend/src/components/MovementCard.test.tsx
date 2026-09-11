import { render, screen } from '@testing-library/react';
import type { MovementSummary } from '../api/types';
import {
  MOVEMENT_DISCLAIMER,
  MOVEMENT_FOOT_UNAVAILABLE,
  MOVEMENT_UNAVAILABLE,
  movementTerms,
  REFERENCE_METHOD_UNAVAILABLE,
  referenceMethodLabels,
} from '../utils/labels';
import { MovementCard } from './MovementCard';

// 진단형·판정형 표현 회귀 방지(DEC-036: 검증 게이트 전에는 참고 범위·판정 문구 금지).
// 발 관절 각도(내번/외번, 진행 각도)를 주장하는 문구도 금지한다. 정강이 IMU 값임을 부정하는
// 면책 문구는 별도로 검사한다.
const banned = /정상|양호|개선|악화|위험|최대 압력|CoP|중등도|평발|족저근막염/;
const footJointClaim = /내번 각도|외번 각도|발 진행각|진행각도/;

const populated: MovementSummary = {
  imuCoverage: 0.92,
  referenceMethod: 'QUIET_STANDING',
  left: {
    frontalTiltDeg: 3.2,
    sagittalRangeDeg: 41.7,
    transverseRangeDeg: 12.5,
    swingPeakAngularVelocityDps: 318.4,
    windowCount: 18,
  },
  right: {
    frontalTiltDeg: -1.8,
    sagittalRangeDeg: 39.1,
    transverseRangeDeg: 10.9,
    swingPeakAngularVelocityDps: 301.6,
    windowCount: 17,
  },
};

describe('MovementCard', () => {
  it.each([
    ['null', null],
    ['undefined(이전 계약 응답)', undefined],
  ])('summary가 %s이면 미제공 안내만 표시하고 지표·면책 문구를 그리지 않는다', (_case, summary) => {
    const { container } = render(<MovementCard summary={summary} />);

    expect(screen.getByRole('heading', { name: movementTerms.cardTitle })).toBeInTheDocument();
    expect(screen.getByText(movementTerms.badge)).toHaveClass('status-badge--neutral');
    expect(screen.getByText(MOVEMENT_UNAVAILABLE)).toBeInTheDocument();
    expect(container.querySelectorAll('.movement-foot')).toHaveLength(0);
    expect(screen.queryByText(movementTerms.frontalTilt)).not.toBeInTheDocument();
    expect(screen.queryByText(MOVEMENT_DISCLAIMER)).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(banned);
  });

  it('양발 지표 4종·걸음 수·IMU 프레임 비율·기준 자세·면책 문구를 표시한다', () => {
    const { container } = render(<MovementCard summary={populated} />);

    expect(screen.getByRole('heading', { name: movementTerms.cardTitle })).toBeInTheDocument();
    expect(screen.getByText('92%')).toBeInTheDocument();
    expect(screen.getByText(referenceMethodLabels.QUIET_STANDING)).toBeInTheDocument();

    const feet = container.querySelectorAll('.movement-foot');
    expect(feet).toHaveLength(2);
    const [left, right] = feet;
    expect(left).toHaveClass('movement-foot--left');
    expect(left).toHaveTextContent('왼발');
    expect(left?.querySelector('.movement-foot__chip--left')).toHaveTextContent('L');
    expect(right).toHaveClass('movement-foot--right');
    expect(right).toHaveTextContent('오른발');
    expect(right?.querySelector('.movement-foot__chip--right')).toHaveTextContent('R');

    for (const term of [
      movementTerms.frontalTilt,
      movementTerms.sagittalRange,
      movementTerms.transverseRange,
      movementTerms.swingPeakAngularVelocity,
      movementTerms.windowCount,
    ]) {
      expect(screen.getAllByText(term)).toHaveLength(2);
    }
    expect(left).toHaveTextContent('3.2°');
    expect(left).toHaveTextContent('41.7°');
    expect(left).toHaveTextContent('12.5°');
    expect(left).toHaveTextContent('318.4°/s');
    expect(left).toHaveTextContent('18회');
    expect(right).toHaveTextContent('-1.8°');
    expect(right).toHaveTextContent('301.6°/s');
    expect(right).toHaveTextContent('17회');

    expect(screen.getByText(movementTerms.frontalTiltSign)).toBeInTheDocument();
    expect(screen.getByText(MOVEMENT_DISCLAIMER)).toBeInTheDocument();
    expect(screen.queryByText('제공 안 됨')).not.toBeInTheDocument();
    expect(screen.queryByText(MOVEMENT_UNAVAILABLE)).not.toBeInTheDocument();
  });

  it('일부 지표·한쪽 발·기준 자세가 null이면 0으로 오해시키지 않고 제공 안 됨으로 표시한다', () => {
    const partial: MovementSummary = {
      imuCoverage: 0.61,
      referenceMethod: null,
      left: {
        frontalTiltDeg: 2.4,
        sagittalRangeDeg: null,
        transverseRangeDeg: 11.2,
        swingPeakAngularVelocityDps: null,
        windowCount: 9,
      },
      right: null,
    };
    const { container } = render(<MovementCard summary={partial} />);

    expect(screen.getByText('61%')).toBeInTheDocument();
    expect(screen.getByText(REFERENCE_METHOD_UNAVAILABLE)).toBeInTheDocument();

    const [left, right] = container.querySelectorAll('.movement-foot');
    // 왼발: null 지표 2개, 오른발: 발 전체 미제공 1개
    expect(screen.getAllByText('제공 안 됨')).toHaveLength(3);
    expect(left?.querySelectorAll('.movement-foot__value--unavailable')).toHaveLength(2);
    expect(left).toHaveTextContent('2.4°');
    expect(left).toHaveTextContent('11.2°');
    expect(left).toHaveTextContent('9회');
    expect(left).not.toHaveTextContent('0°');
    expect(right?.querySelector('.movement-foot__metrics')).toBeNull();
    expect(right).toHaveTextContent(MOVEMENT_FOOT_UNAVAILABLE);
    expect(right).toHaveTextContent('오른발');
    expect(screen.getByText(MOVEMENT_DISCLAIMER)).toBeInTheDocument();
  });

  it('windowCount 0이고 네 지표가 모두 null인 발은 걸음 수 0회와 제공 안 됨을 함께 표시한다', () => {
    const empty: MovementSummary = {
      ...populated,
      right: {
        frontalTiltDeg: null,
        sagittalRangeDeg: null,
        transverseRangeDeg: null,
        swingPeakAngularVelocityDps: null,
        windowCount: 0,
      },
    };
    const { container } = render(<MovementCard summary={empty} />);
    const right = container.querySelectorAll('.movement-foot')[1];

    expect(right?.querySelectorAll('.movement-foot__value--unavailable')).toHaveLength(4);
    expect(right).toHaveTextContent('0회');
  });

  it('compact 모드는 eyebrow·부호 안내를 생략하고 지표·면책 문구는 유지한다', () => {
    const { container } = render(<MovementCard compact summary={populated} />);

    expect(container.querySelector('.movement-card')).toHaveClass('movement-card--compact');
    expect(container.querySelector('.eyebrow')).toBeNull();
    expect(screen.queryByText(movementTerms.frontalTiltSign)).not.toBeInTheDocument();
    expect(screen.getAllByText(movementTerms.frontalTilt)).toHaveLength(2);
    expect(screen.getByText(MOVEMENT_DISCLAIMER)).toBeInTheDocument();
    expect(screen.getByText(movementTerms.badge)).toBeInTheDocument();
  });

  it.each([
    ['null', null],
    ['양발 값 있음', populated],
    ['기준 자세 미확보·한쪽 발 null', { ...populated, referenceMethod: null, right: null }],
  ])(
    "카드(%s)에 판정 어휘·'최대 압력'·'CoP'·발 관절 각도 주장이 없고 정강이·기능 검증용을 명시한다",
    (_case, summary) => {
      const { container } = render(<MovementCard summary={summary} />);
      const text = container.textContent;
      expect(text).not.toMatch(banned);
      expect(text).not.toMatch(footJointClaim);
      expect(text).toContain('정강이');
      expect(text).toContain('기능 검증용');
      const labels = Array.from(container.querySelectorAll('[aria-label]')).map(
        (node) => node.getAttribute('aria-label') ?? '',
      );
      expect(labels.join(' ')).not.toMatch(banned);
    },
  );
});
