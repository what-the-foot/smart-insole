import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './AppShell';
import { ProtectedRoute, PublicOnlyRoute } from '../features/auth/ProtectedRoute';
import { DashboardPage } from '../pages/DashboardPage';
import { DevicesPage } from '../pages/DevicesPage';
import { HistoryPage } from '../pages/HistoryPage';
import { LiveMeasurementPage } from '../pages/LiveMeasurementPage';
import { NewMeasurementPage } from '../pages/NewMeasurementPage';
import { RecommendationPage } from '../pages/RecommendationPage';
import { ResultPage } from '../pages/ResultPage';
import { SigninPage } from '../pages/SigninPage';
import { SignupPage } from '../pages/SignupPage';
import { NotFoundPage } from '../components/StatusUi';
import { useAuth } from '../features/auth/AuthContext';

function RootRedirect() {
  const { isAuthenticated } = useAuth();
  return <Navigate replace to={isAuthenticated ? '/dashboard' : '/login'} />;
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
          <Route element={<LiveMeasurementPage />} path="/measurements/:sessionId/live" />
          <Route element={<ResultPage />} path="/measurements/:sessionId/result" />
          <Route element={<HistoryPage />} path="/history" />
          <Route element={<RecommendationPage />} path="/recommendations/:code" />
          <Route element={<NotFoundPage />} path="*" />
        </Route>
      </Route>
      <Route element={<NotFoundPage />} path="*" />
    </Routes>
  );
}
