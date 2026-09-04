import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { Icon } from '../../components/Icon';
import { Spinner } from '../../components/StatusUi';
import { isBcryptLengthSupported } from '../../utils/validation';
import { useAuth } from './AuthContext';

export type ReauthReason = 'EXPIRING' | 'EXPIRED';

const reasonCopy: Record<ReauthReason, { title: string; description: string }> = {
  EXPIRING: {
    title: '로그인 세션이 곧 만료됩니다',
    description: '지금 다시 로그인하면 측정 화면을 벗어나지 않고 실시간 연결을 이어갈 수 있습니다.',
  },
  EXPIRED: {
    title: '로그인 세션이 만료되었습니다',
    description: '다시 로그인하면 같은 세션의 실시간 연결을 새 토큰으로 다시 구독합니다.',
  },
};

// 현재 화면을 유지한 채 토큰만 교체하는 재로그인 다이얼로그. 성공하면 AuthProvider 세션이 갱신되어
// STOMP 훅이 새 connectHeaders로 재구독한다(FE-6).
export function ReauthDialog({
  open,
  reason,
  onClose,
}: {
  open: boolean;
  reason: ReauthReason;
  onClose: () => void;
}) {
  const { user, signin } = useAuth();
  const titleId = useId();
  const passwordRef = useRef<HTMLInputElement>(null);
  const [email, setEmail] = useState(user?.email ?? '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setPassword('');
    setError(null);
    setEmail(user?.email ?? '');
    passwordRef.current?.focus();
  }, [open, user?.email]);

  if (!open) return null;

  const handleSubmit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!isBcryptLengthSupported(password)) {
      setError('비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await signin({ email, password });
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '다시 로그인하지 못했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="reauth-backdrop"
      onKeyDown={(event) => {
        if (event.key === 'Escape') onClose();
      }}
      role="presentation"
    >
      <section aria-labelledby={titleId} aria-modal="true" className="reauth-dialog" role="dialog">
        <p className="eyebrow">SESSION</p>
        <h2 id={titleId}>{reasonCopy[reason].title}</h2>
        <p className="muted">{reasonCopy[reason].description}</p>
        {error ? <p className="form-error" role="alert"><Icon name="alert" />{error}</p> : null}
        <form className="form-stack" onSubmit={handleSubmit}>
          <label className="field">
            <span>이메일</span>
            <input autoComplete="email" name="email" onChange={(event) => setEmail(event.target.value)} readOnly={Boolean(user)} required type="email" value={email} />
          </label>
          <label className="field">
            <span>비밀번호</span>
            <input autoComplete="current-password" maxLength={100} name="password" onChange={(event) => setPassword(event.target.value)} ref={passwordRef} required type="password" value={password} />
          </label>
          <div className="form-actions">
            <button className="button button--secondary" onClick={onClose} type="button">나중에</button>
            <button className="button" disabled={submitting} type="submit">{submitting ? <Spinner label="로그인 중" /> : '다시 로그인'}</button>
          </div>
        </form>
      </section>
    </div>
  );
}
