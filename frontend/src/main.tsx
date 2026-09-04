import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './api/queryClient';
import { App } from './app/App';
import { AppErrorBoundary } from './app/AppErrorBoundary';
import { AuthProvider } from './features/auth/AuthContext';
import './styles/global.css';

const root = document.getElementById('root');
if (!root) throw new Error('애플리케이션 루트 요소를 찾을 수 없습니다.');

createRoot(root).render(
  <StrictMode>
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <BrowserRouter><App /></BrowserRouter>
        </AuthProvider>
      </QueryClientProvider>
    </AppErrorBoundary>
  </StrictMode>,
);
