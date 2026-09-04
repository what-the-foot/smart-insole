import axios, { type AxiosError, type AxiosRequestConfig } from 'axios';
import { clearAuthSession, readAuthSession } from '../features/auth/authSession';
import { API_BASE_URL } from './config';
import { isRecord, isRfc3339DateTime } from './runtimeValidation';
import type { ApiErrorBody } from './types';

export class ApiError extends Error {
  readonly code: string;
  readonly status: number | null;
  readonly details: Record<string, unknown>;
  readonly traceId: string | null;

  constructor(options: {
    message: string;
    code?: string;
    status?: number;
    details?: Record<string, unknown>;
    traceId?: string;
  }) {
    super(options.message);
    this.name = 'ApiError';
    this.code = options.code ?? 'NETWORK_ERROR';
    this.status = options.status ?? null;
    this.details = options.details ?? {};
    this.traceId = options.traceId ?? null;
  }
}

const isApiErrorBody = (value: unknown): value is ApiErrorBody => {
  if (!isRecord(value)) return false;
  const keys = Object.keys(value);
  return (
    keys.length === 5 &&
    ['code', 'message', 'details', 'traceId', 'timestamp'].every((key) =>
      Object.prototype.hasOwnProperty.call(value, key),
    ) &&
    typeof value.code === 'string' &&
    typeof value.message === 'string' &&
    isRecord(value.details) &&
    typeof value.traceId === 'string' &&
    isRfc3339DateTime(value.timestamp)
  );
};

export const toApiError = (error: unknown): ApiError => {
  if (error instanceof ApiError) return error;
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError;
    const body = axiosError.response?.data;
    if (isApiErrorBody(body)) {
      return new ApiError({
        message: body.message,
        code: body.code,
        ...(axiosError.response ? { status: axiosError.response.status } : {}),
        details: body.details,
        traceId: body.traceId,
      });
    }
    return new ApiError({
      message: axiosError.response
        ? '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
        : '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
      code: axiosError.code ?? 'NETWORK_ERROR',
      ...(axiosError.response ? { status: axiosError.response.status } : {}),
    });
  }
  return new ApiError({ message: '예상하지 못한 오류가 발생했습니다.', code: 'UNKNOWN_ERROR' });
};

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config) => {
  const session = readAuthSession();
  if (session) config.headers.set('Authorization', `Bearer ${session.accessToken}`);
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401)
      clearAuthSession('UNAUTHORIZED');
    return Promise.reject(toApiError(error));
  },
);

export const request = async <T>(config: AxiosRequestConfig): Promise<T> => {
  try {
    const response = await apiClient.request<T>(config);
    return response.data;
  } catch (error) {
    throw toApiError(error);
  }
};
