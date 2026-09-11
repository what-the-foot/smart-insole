import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  ContactTimeBars,
  Donut,
  KpiCard,
  PatternList,
  RecommendationTile,
  RegionDistributionChart,
  Sparkline,
  TrendLineChart,
} from './index';

// 진단형·판정형 표현 회귀 방지. 차트 프리미티브가 스스로 만들어 내는 문구에는
// 판정 어휘가 없어야 한다(배지·제목 문구는 호출자가 백엔드 라벨로 넘긴다).
const banned = /정상|양호|개선|악화|위험|최대 압력|CoP|중등도|압력 중심|총압력|평발|족저근막염/;

describe('charts wording', () => {
  it('모든 차트 프리미티브의 기본 문구에 판정 어휘가 없다', () => {
    const { container } = render(
      <MemoryRouter>
        <KpiCard icon={null} label="좌우 대칭 지수" sub="접촉 시간 차이 %" value={12.4} />
        <RegionDistributionChart
          rows={[
            { label: '후족부', left: 0.4, right: 0.5 },
            { label: '중족부', left: null, right: null },
          ]}
        />
        <ContactTimeBars leftMs={256} rightMs={null} />
        <Donut ariaLabel="왼발 52%, 오른발 48%" centerLabel="52 : 48" leftPct={52} rightPct={48} />
        <TrendLineChart
          ariaSummary="최근 3회 좌우 대칭 지수"
          points={[
            { label: '5/12', value: 12 },
            { label: '5/13', value: null },
            { label: '5/14', value: 9 },
          ]}
          unit="지수"
        />
        <PatternList
          patterns={[
            {
              code: 'LOW_HALLUX_SIGNAL',
              title: '엄지 신호 낮음',
              message: '엄지 부위 센서 신호가 낮게 관찰되었습니다.',
              severity: 'RECHECK',
              observationLevel: 'PARTIALLY_OBSERVED',
            },
          ]}
        />
        <PatternList patterns={[]} />
        <RecommendationTile
          code="BALANCED_FOOT_LOADING"
          durationMinutes={8}
          summary="양발에 고르게 서는 연습입니다."
          title="양발 균등 하중 연습"
        />
        <Sparkline values={[1, null, 2]} />
        <Sparkline values={[]} />
      </MemoryRouter>,
    );

    expect(container.textContent).not.toMatch(banned);
    const labels = Array.from(container.querySelectorAll('[aria-label]')).map(
      (node) => node.getAttribute('aria-label') ?? '',
    );
    expect(labels.join(' ')).not.toMatch(banned);
  });
});
