import type { ReactNode } from 'react';
import type { ObservationLevel, PatternSeverity } from '../../api/types';
import { StatusBadge } from '../StatusUi';
import { observationLevelLabels } from '../../utils/labels';
import { observationTone, severityLabels, severityTone } from './chartShared';

export interface PatternListItem {
  code: string;
  /** 백엔드 title/message를 그대로 표시한다. 프론트에서 질환명·판정을 덧붙이지 않는다. */
  title: string;
  message: string;
  severity: PatternSeverity;
  /** rule-v1.2.0 이상. 이전 결과는 null → 관찰 단계 배지 생략. */
  observationLevel?: ObservationLevel | null;
  /** 예) occurrenceRateText(pattern) 결과 '발생 비율 62% (13/21 걸음)'. */
  occurrenceText?: string | null;
}

export interface PatternListProps {
  patterns: readonly PatternListItem[];
  /** 코드별 아이콘. Icon.tsx는 다른 모듈이 소유하므로 렌더 함수로 받는다. */
  renderIcon?: (code: string) => ReactNode;
  emptyText?: string;
  ariaLabel?: string;
}

// 관찰된 패턴 목록(대시보드용 압축 행). 결과 페이지의 .pattern-card 개수를 세는 테스트가 있어
// 같은 클래스를 쓰지 않는다.
export function PatternList({
  patterns,
  renderIcon,
  emptyText = '표시할 관찰 패턴이 없습니다.',
  ariaLabel = '관찰된 패턴',
}: PatternListProps) {
  if (patterns.length === 0) {
    return <p className="pattern-rows__empty">{emptyText}</p>;
  }
  return (
    <ul aria-label={ariaLabel} className="pattern-rows">
      {patterns.map((pattern, index) => {
        const level = pattern.observationLevel ?? null;
        const icon = renderIcon ? renderIcon(pattern.code) : null;
        return (
          <li
            className={`pattern-row pattern-row--${pattern.severity.toLowerCase()}`}
            key={`${pattern.code}-${index}`}
          >
            <span aria-hidden="true" className="pattern-row__icon">
              {icon}
            </span>
            <div className="pattern-row__body">
              <div className="pattern-row__head">
                <h3 className="pattern-row__title">{pattern.title}</h3>
                <div className="pattern-row__badges">
                  <StatusBadge tone={severityTone(pattern.severity)}>
                    {severityLabels[pattern.severity]}
                  </StatusBadge>
                  {level ? (
                    <StatusBadge tone={observationTone(level)}>
                      {observationLevelLabels[level]}
                    </StatusBadge>
                  ) : null}
                </div>
              </div>
              <p className="pattern-row__message">{pattern.message}</p>
              {pattern.occurrenceText ? (
                <p className="pattern-row__occurrence">{pattern.occurrenceText}</p>
              ) : null}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
