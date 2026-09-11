import { useId, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

export interface RecommendationTileProps {
  code: string;
  /** 백엔드 RecommendationSummary의 title/summary를 그대로 표시한다. */
  title: string;
  summary: string;
  durationMinutes: number;
  /** 장식용 아이콘(예: <Icon name="sparkles" />). */
  icon?: ReactNode;
}

// 운동 가이드 타일. 링크 이름은 '가이드 확인'으로 고정하고 제목은 aria-describedby로 이어 준다.
export function RecommendationTile({
  code,
  title,
  summary,
  durationMinutes,
  icon,
}: RecommendationTileProps) {
  const titleId = useId();
  return (
    <div className="reco-tile">
      {icon ? (
        <span aria-hidden="true" className="reco-tile__art">
          {icon}
        </span>
      ) : null}
      <div className="reco-tile__body">
        <p className="reco-tile__eyebrow">약 {durationMinutes}분</p>
        <h3 className="reco-tile__title" id={titleId}>
          {title}
        </h3>
        <p className="reco-tile__summary">{summary}</p>
        <Link
          aria-describedby={titleId}
          className="reco-tile__link"
          to={`/recommendations/${encodeURIComponent(code)}`}
        >
          가이드 확인
          <span aria-hidden="true" className="reco-tile__arrow">
            →
          </span>
        </Link>
      </div>
    </div>
  );
}
