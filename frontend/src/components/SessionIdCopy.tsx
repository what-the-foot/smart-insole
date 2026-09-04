import { useEffect, useState } from 'react';
import { copyTextToClipboard } from '../utils/clipboard';
import { Icon } from './Icon';

type CopyState = 'idle' | 'copied' | 'failed';

// 수신기 CLI(`run --session-id ...`)에 붙여 넣을 세션 ID. 클립보드 실패 시 수동 선택을 안내한다.
export function SessionIdCopy({ sessionId }: { sessionId: string }) {
  const [state, setState] = useState<CopyState>('idle');

  useEffect(() => {
    if (state === 'idle') return;
    const timer = window.setTimeout(() => setState('idle'), 2_500);
    return () => window.clearTimeout(timer);
  }, [state]);

  const handleCopy = async () => {
    const copied = await copyTextToClipboard(sessionId);
    setState(copied ? 'copied' : 'failed');
  };

  return (
    <div className="session-id-copy">
      <div>
        <span>세션 ID (수신기 CLI --session-id)</span>
        <code>{sessionId}</code>
      </div>
      <button className="button button--secondary button--compact" onClick={() => void handleCopy()} type="button">
        <Icon name={state === 'copied' ? 'check' : 'device'} />
        세션 ID 복사
      </button>
      <p aria-live="polite" className={`session-id-copy__status${state === 'failed' ? ' session-id-copy__status--error' : ''}`}>
        {state === 'copied' ? '복사했습니다.' : state === 'failed' ? '복사하지 못했습니다. ID를 직접 선택해 복사해 주세요.' : ''}
      </p>
    </div>
  );
}
