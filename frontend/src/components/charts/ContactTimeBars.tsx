import { formatNumber } from '../../utils/format';
import { footLabels, maxOf, shareOfMax, UNAVAILABLE_TEXT, type FootKey } from './chartShared';

export interface ContactTimeBarsProps {
  /** 평균 접촉 창 길이(밀리초). 값이 없으면 null. */
  leftMs: number | null;
  rightMs: number | null;
  ariaLabel?: string;
}

const millisText = (value: number | null): string =>
  value === null ? UNAVAILABLE_TEXT : `${formatNumber(value)}밀리초`;

// 좌우 접촉 시간 두 막대. 긴 쪽을 100%로 둔 상대 길이이며 값과 차이는 텍스트로 함께 적는다.
// 접촉 창은 센서 신호 기준 접촉 구간이며 임상 보행 단계가 아니다(판정 문구 없음).
export function ContactTimeBars({ leftMs, rightMs, ariaLabel }: ContactTimeBarsProps) {
  const max = maxOf([leftMs, rightMs]);
  const diffText =
    leftMs !== null && rightMs !== null
      ? `차이 ${formatNumber(Math.abs(leftMs - rightMs))}밀리초`
      : `차이 ${UNAVAILABLE_TEXT}`;
  const rows: readonly { key: FootKey; label: string; value: number | null }[] = [
    { key: 'left', label: footLabels.left, value: leftMs },
    { key: 'right', label: footLabels.right, value: rightMs },
  ];
  return (
    <div aria-label={ariaLabel ?? '좌우 접촉 시간'} className="contact-bars" role="group">
      <ul className="contact-bars__rows">
        {rows.map((row) => (
          <li className={`contact-bars__row contact-bars__row--${row.key}`} key={row.key}>
            <span className="contact-bars__side">{row.label}</span>
            <span aria-hidden="true" className="contact-bars__track">
              <span
                className="contact-bars__fill"
                style={{ width: `${shareOfMax(row.value, max) * 100}%` }}
              />
            </span>
            <span
              className={`contact-bars__value${row.value === null ? ' contact-bars__value--empty' : ''}`}
            >
              {millisText(row.value)}
            </span>
          </li>
        ))}
      </ul>
      <p className="contact-bars__diff">{diffText}</p>
    </div>
  );
}
