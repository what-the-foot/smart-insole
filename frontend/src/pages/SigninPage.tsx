import { useState, type SyntheticEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Icon } from '../components/Icon';
import { Spinner } from '../components/StatusUi';
import { useAuth } from '../features/auth/AuthContext';
import { consumeLastSignoutReason, type SignoutReason } from '../features/auth/authSession';
import { isBcryptLengthSupported } from '../utils/validation';

interface SigninLocationState {
  from?: string;
  notice?: string;
}

// 직접 로그아웃(USER)은 안내하지 않는다.
const signoutReasonNotices: Record<SignoutReason, string | null> = {
  EXPIRED: '로그인 세션이 만료되어 로그아웃되었습니다. 다시 로그인하면 이어서 사용할 수 있습니다.',
  UNAUTHORIZED: '인증이 만료되었거나 유효하지 않아 로그아웃되었습니다. 다시 로그인해 주세요.',
  USER: null,
};

export function SigninPage() {
  const { signin } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state as SigninLocationState | null;
  const [signoutNotice] = useState<string | null>(() => {
    const reason = consumeLastSignoutReason();
    return reason ? signoutReasonNotices[reason] : null;
  });
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

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
      void navigate(state?.from ?? '/dashboard', { replace: true });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '로그인하지 못했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="auth-page">
      <section className="auth-intro" aria-label="서비스 소개">
        <Link className="brand brand--light" to="/login">
          <span className="brand__mark">
            <Icon name="activity" />
          </span>
          <span>바른걸음</span>
        </Link>
        <div className="auth-intro__content">
          <p className="eyebrow eyebrow--light">SMART INSOLE COMPANION</p>
          <h1>
            걸음의 변화를
            <br />
            차분하게 살펴보세요.
          </h1>
          <p>양발 압력과 보행 패턴을 한눈에 확인하고, 다음 운동을 준비할 수 있습니다.</p>
        </div>
        <div className="auth-intro__footprint" aria-hidden="true">
          <span />
          <span />
          <span />
          <span />
          <span />
        </div>
      </section>

      <section className="auth-form-panel" aria-labelledby="signin-title">
        <div className="auth-form-card">
          <p className="eyebrow">다시 만나 반가워요</p>
          <h2 id="signin-title">로그인</h2>
          <p className="muted">측정 기록과 운동 가이드를 이어서 확인하세요.</p>
          {state?.notice ? (
            <p className="notice notice--success" role="status">
              <Icon name="check" />
              {state.notice}
            </p>
          ) : null}
          {signoutNotice ? (
            <p className="notice notice--info" role="status">
              <Icon name="clock" />
              {signoutNotice}
            </p>
          ) : null}
          {error ? (
            <p className="form-error" role="alert">
              <Icon name="alert" />
              {error}
            </p>
          ) : null}
          <form className="form-stack" onSubmit={handleSubmit}>
            <label className="field">
              <span>이메일</span>
              <input
                autoComplete="email"
                inputMode="email"
                name="email"
                onChange={(event) => setEmail(event.target.value)}
                placeholder="name@example.com"
                required
                type="email"
                value={email}
              />
            </label>
            <label className="field">
              <span>비밀번호</span>
              <input
                autoComplete="current-password"
                maxLength={100}
                minLength={1}
                name="password"
                onChange={(event) => setPassword(event.target.value)}
                placeholder="비밀번호를 입력하세요"
                required
                type="password"
                value={password}
              />
            </label>
            <button
              className="button button--full button--large"
              disabled={submitting}
              type="submit"
            >
              {submitting ? <Spinner label="로그인 중" /> : '로그인'}
            </button>
          </form>
          <p className="auth-switch">
            아직 계정이 없나요? <Link to="/signup">회원가입</Link>
          </p>
          <p className="security-note">
            <Icon name="shield" />이 브라우저 탭을 닫으면 로그인 정보가 삭제됩니다.
          </p>
        </div>
      </section>
    </main>
  );
}
