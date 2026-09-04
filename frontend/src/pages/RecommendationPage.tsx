import { Link, useParams } from 'react-router-dom';
import { useRecommendation } from '../api/queries';
import { Icon } from '../components/Icon';
import { ErrorPanel, PageHeader, Spinner, StatePanel } from '../components/StatusUi';
import { patternCodeLabel } from '../utils/labels';

export function RecommendationPage() {
  const { code } = useParams();
  const recommendation = useRecommendation(code);

  if (!code) return <StatePanel icon="sparkles" title="운동 가이드를 찾을 수 없어요" description="측정 결과에서 운동 가이드를 다시 선택해 주세요." action={<Link className="button" to="/history">측정 기록에서 다시 찾기</Link>} />;
  if (recommendation.isPending) return <div className="centered-status"><Spinner label="운동 가이드 불러오는 중" /></div>;
  if (recommendation.isError) return <ErrorPanel error={recommendation.error} retry={() => void recommendation.refetch()} />;

  const guide = recommendation.data;

  return (
    <div className="page-stack recommendation-page">
      <PageHeader eyebrow="MOVEMENT GUIDE" title={guide.title} description="측정 결과와 연결된 운동 가이드를 안전하게 확인하세요." />
      <section className="recommendation-hero"><div className="recommendation-hero__art"><Icon name="sparkles" /><span /><span /><span /></div><div><p className="eyebrow eyebrow--light">예상 소요 시간</p><strong>{guide.durationMinutes}<small>분</small></strong><p>{guide.purpose}</p></div></section>
      <section className="content-card guide-steps" aria-labelledby="guide-steps-title"><div className="section-heading"><div><p className="eyebrow">HOW TO MOVE</p><h2 id="guide-steps-title">수행 순서</h2><p>첫 단계의 시작 자세부터 순서대로 천천히 진행하세요.</p></div></div><ol>{guide.instructions.map((instruction, index) => <li key={`${index}-${instruction}`}><span>{index + 1}</span><p>{instruction}</p></li>)}</ol></section>
      {guide.relatedPatternCodes.length ? <section className="content-card" aria-labelledby="related-pattern-title"><div className="section-heading"><div><p className="eyebrow">RELATED PATTERNS</p><h2 id="related-pattern-title">관련 관찰 패턴</h2></div></div><ul className="related-patterns">{guide.relatedPatternCodes.map((pattern) => <li key={pattern}>{patternCodeLabel(pattern)}</li>)}</ul></section> : null}
      <aside className="safety-card" aria-labelledby="safety-title"><Icon name="shield" /><div><h2 id="safety-title">운동 전 안전 안내</h2><p>{guide.cautionText}</p><ul><li>현재 몸 상태와 주변 환경을 먼저 확인하세요.</li><li>통증이나 어지럼, 불편함이 느껴지면 즉시 중단하세요.</li><li>불편함이 지속되면 전문가의 평가가 필요할 수 있습니다.</li></ul><p>이 운동 가이드는 치료나 교정 효과를 보장하지 않습니다.</p></div></aside>
      <div className="result-actions"><Link className="button button--secondary" to="/history">측정 기록으로</Link></div>
    </div>
  );
}
