import { useState, type SyntheticEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Icon } from '../components/Icon';
import { Spinner } from '../components/StatusUi';
import { useAuth } from '../features/auth/AuthContext';
import { isBcryptLengthSupported } from '../utils/validation';

const BRAND_NAME = '바른걸음';
const BRAND_TAGLINE = 'Smart Insole for Better Steps';

export function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (password !== passwordConfirm) {
      setError('비밀번호가 서로 일치하지 않습니다.');
      return;
    }
    if (!isBcryptLengthSupported(password)) {
      setError('비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await signup({ name, email, password });
      void navigate('/login', {
        replace: true,
        state: { notice: '회원가입이 완료되었습니다. 새 계정으로 로그인해 주세요.' },
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '회원가입하지 못했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="auth-page">
      <section className="auth-intro auth-intro--signup" aria-label="서비스 소개">
        <Link className="brand brand--light auth-brand" to="/login">
          <span className="brand__mark">
            <Icon name="foot" />
          </span>
          <span className="brand__text">
            <strong>{BRAND_NAME}</strong>
            <small>{BRAND_TAGLINE}</small>
          </span>
        </Link>
        <div className="auth-intro__content">
          <p className="eyebrow eyebrow--light">작은 관찰에서 시작해요</p>
          <h1>
            오늘의 걸음을
            <br />
            안전하게 기록하세요.
          </h1>
          <p>이 서비스는 의료 진단이 아니며, 걸음 습관 관찰과 운동 가이드를 돕는 도구입니다.</p>
          <ol className="auth-intro__steps">
            <li>
              <span aria-hidden="true">1</span>
              <span>왼발·오른발 인솔 등록</span>
            </li>
            <li>
              <span aria-hidden="true">2</span>
              <span>수신기 연결 후 평소처럼 걷기</span>
            </li>
            <li>
              <span aria-hidden="true">3</span>
              <span>관찰 패턴과 운동 가이드 확인</span>
            </li>
          </ol>
        </div>
        <p className="auth-intro__note">
          <Icon name="shield" />
          비밀번호는 안전하게 해시로만 저장됩니다.
        </p>
      </section>
      <section className="auth-form-panel" aria-labelledby="signup-title">
        <div className="auth-form-card">
          <div aria-hidden="true" className="auth-form-card__brand">
            <span className="brand__mark">
              <Icon name="foot" />
            </span>
            <span className="brand__text">
              <strong>{BRAND_NAME}</strong>
              <small>{BRAND_TAGLINE}</small>
            </span>
          </div>
          <p className="eyebrow">처음 오셨나요?</p>
          <h2 id="signup-title">회원가입</h2>
          <p className="muted">기본 정보만 입력하면 바로 시작할 수 있습니다.</p>
          {error ? (
            <p className="form-error" role="alert">
              <Icon name="alert" />
              {error}
            </p>
          ) : null}
          <form className="form-stack" onSubmit={handleSubmit}>
            <label className="field">
              <span>이름</span>
              <input
                autoComplete="name"
                maxLength={50}
                onChange={(event) => setName(event.target.value)}
                required
                value={name}
              />
            </label>
            <label className="field">
              <span>이메일</span>
              <input
                autoComplete="email"
                inputMode="email"
                onChange={(event) => setEmail(event.target.value)}
                required
                type="email"
                value={email}
              />
            </label>
            <label className="field">
              <span>비밀번호</span>
              <input
                aria-describedby="password-help"
                autoComplete="new-password"
                minLength={8}
                maxLength={100}
                onChange={(event) => setPassword(event.target.value)}
                required
                type="password"
                value={password}
              />
              <small id="password-help">
                8자 이상이며 UTF-8 기준 72바이트 이하로 입력해 주세요.
              </small>
            </label>
            <label className="field">
              <span>비밀번호 확인</span>
              <input
                autoComplete="new-password"
                minLength={8}
                maxLength={100}
                onChange={(event) => setPasswordConfirm(event.target.value)}
                required
                type="password"
                value={passwordConfirm}
              />
            </label>
            <button
              className="button button--full button--large"
              disabled={submitting}
              type="submit"
            >
              {submitting ? <Spinner label="가입 중" /> : '계정 만들기'}
            </button>
          </form>
          <p className="auth-switch">
            이미 계정이 있나요? <Link to="/login">로그인</Link>
          </p>
        </div>
      </section>
    </main>
  );
}
