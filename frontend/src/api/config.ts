const stripTrailingSlash = (value: string): string => value.replace(/\/$/, '');

export const API_BASE_URL = stripTrailingSlash(
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
);

export const WS_URL = import.meta.env.VITE_WS_URL ?? `${API_BASE_URL.replace(/^http/, 'ws')}/ws`;

// 원시 설정 문자열. 50/100 검증과 기본값 50 처리는 features/measurement/sampleRates.ts가 담당한다.
export const DEFAULT_SAMPLE_RATE_HZ_SETTING: string | undefined = import.meta.env
  .VITE_DEFAULT_SAMPLE_RATE_HZ;

// 개발 모드에서만 '시뮬레이션 세션'(sourceType SIMULATED) 옵션을 노출한다(DEC-028).
export const SIMULATION_SESSION_OPTION_ENABLED: boolean = import.meta.env.DEV;

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
  recommendation: (code: string) => `/api/v1/recommendations/${encodeURIComponent(code)}`,
  pressureTopic: (sessionId: string) =>
    `/topic/measurement-sessions/${encodeURIComponent(sessionId)}/pressure`,
} as const;
