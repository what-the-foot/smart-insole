const stripTrailingSlash = (value: string): string => value.replace(/\/$/, '');

export const API_BASE_URL = stripTrailingSlash(
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
);

export const WS_URL =
  import.meta.env.VITE_WS_URL ?? `${API_BASE_URL.replace(/^http/, 'ws')}/ws`;

export const endpoints = {
  signup: '/api/v1/auth/signup',
  signin: '/api/v1/auth/signin',
  devices: '/api/v1/devices',
  sensorLayout: (version: string) => `/api/v1/sensor-layouts/${encodeURIComponent(version)}`,
  measurementSessions: '/api/v1/measurement-sessions',
  measurementSession: (sessionId: string) =>
    `/api/v1/measurement-sessions/${encodeURIComponent(sessionId)}`,
  startMeasurement: (sessionId: string) =>
    `/api/v1/measurement-sessions/${encodeURIComponent(sessionId)}/start`,
  completeMeasurement: (sessionId: string) =>
    `/api/v1/measurement-sessions/${encodeURIComponent(sessionId)}/complete`,
  cancelMeasurement: (sessionId: string) =>
    `/api/v1/measurement-sessions/${encodeURIComponent(sessionId)}/cancel`,
  realtimeSnapshot: (sessionId: string) =>
    `/api/v1/measurement-sessions/${encodeURIComponent(sessionId)}/realtime-snapshot`,
  result: (sessionId: string) =>
    `/api/v1/measurement-sessions/${encodeURIComponent(sessionId)}/result`,
  recommendation: (code: string) =>
    `/api/v1/recommendations/${encodeURIComponent(code)}`,
  pressureTopic: (sessionId: string) =>
    `/topic/measurement-sessions/${encodeURIComponent(sessionId)}/pressure`,
} as const;
