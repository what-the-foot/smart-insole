import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { ProtectedRoute, PublicOnlyRoute } from '../features/auth/ProtectedRoute';
import { DashboardPage } from '../pages/DashboardPage';
import { DevicesPage } from '../pages/DevicesPage';
import { HelpPage } from '../pages/HelpPage';
import { HistoryPage } from '../pages/HistoryPage';
import { LiveEntryPage } from '../pages/LiveEntryPage';
import { LiveMeasurementPage } from '../pages/LiveMeasurementPage';
import { NewMeasurementPage } from '../pages/NewMeasurementPage';
import { RecommendationPage } from '../pages/RecommendationPage';
import { RecommendationsIndexPage } from '../pages/RecommendationsIndexPage';
import { ResultPage } from '../pages/ResultPage';
import { ResultsEntryPage } from '../pages/ResultsEntryPage';
import { SettingsPage } from '../pages/SettingsPage';
import { SigninPage } from '../pages/SigninPage';
import { SignupPage } from '../pages/SignupPage';
import { NotFoundPage, StatePanel } from '../components/StatusUi';
import { useAuth } from '../features/auth/AuthContext';

function RootRedirect() {
  const { isAuthenticated } = useAuth();
  return <Navigate replace to={isAuthenticated ? '/dashboard' : '/login'} />;
}

// 셸 안의 404. AppShell이 이미 <main>을 렌더하므로 NotFoundPage(standalone <main>)를 중첩하지 않는다.
function ShellNotFound() {
  return (
    <div className="standalone-page-inner">
      <StatePanel
        icon="activity"
        title="페이지를 찾을 수 없어요"
        description="주소를 다시 확인하거나 홈으로 돌아가 주세요."
        action={
          <Link className="button" to="/dashboard">
            홈으로
          </Link>
        }
      />
    </div>
  );
}

export function App() {
  return (
    <Routes>
      <Route element={<RootRedirect />} path="/" />
      <Route element={<PublicOnlyRoute />}>
        <Route element={<SigninPage />} path="/login" />
        <Route element={<SignupPage />} path="/signup" />
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route element={<DashboardPage />} path="/dashboard" />
          <Route element={<DevicesPage />} path="/devices" />
          <Route element={<NewMeasurementPage />} path="/measurements/new" />
          <Route element={<LiveEntryPage />} path="/measurements/live" />
          <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
          <Route element={<ResultPage />} path="/measurements/:sessionId/result" />
          <Route element={<ResultsEntryPage />} path="/results" />
          <Route element={<HistoryPage />} path="/history" />
          <Route element={<RecommendationsIndexPage />} path="/recommendations" />
          <Route element={<RecommendationPage />} path="/recommendations/:code" />
          <Route element={<SettingsPage />} path="/settings" />
          <Route element={<HelpPage />} path="/help" />
          <Route element={<ShellNotFound />} path="*" />
        </Route>
      </Route>
      <Route element={<NotFoundPage />} path="*" />
    </Routes>
  );
}
