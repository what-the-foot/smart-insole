import { apiClient, request, toApiError } from './client';
import { endpoints } from './config';
import { parseSensorLayoutValue } from './runtimeValidation';
import { parseRealtimeValue } from '../features/realtime/realtimeSchema';
import type {
  AnalysisPendingResponse,
  AnalysisResultResponse,
  AuthTokenResponse,
  CreateMeasurementSessionRequest,
  DeviceResponse,
  MeasurementSessionPage,
  MeasurementSessionResponse,
  MeasurementListQuery,
  RecommendationDetailResponse,
  RegisterDeviceRequest,
  ResultResponse,
  SigninRequest,
  SignupRequest,
  UserSummary,
} from './types';

export const authApi = {
  signup: (payload: SignupRequest) =>
    request<UserSummary>({ method: 'POST', url: endpoints.signup, data: payload }),
  signin: (payload: SigninRequest) =>
    request<AuthTokenResponse>({ method: 'POST', url: endpoints.signin, data: payload }),
};

export const deviceApi = {
  list: () => request<DeviceResponse[]>({ method: 'GET', url: endpoints.devices }),
  register: (payload: RegisterDeviceRequest) =>
    request<DeviceResponse>({ method: 'POST', url: endpoints.devices, data: payload }),
  getLayout: async (version: string) =>
    parseSensorLayoutValue(
      await request<unknown>({ method: 'GET', url: endpoints.sensorLayout(version) }),
    ),
};

export type MeasurementListParams = Required<Pick<MeasurementListQuery, 'page' | 'size'>> &
  Omit<MeasurementListQuery, 'page' | 'size'>;

export const measurementApi = {
  create: (payload: CreateMeasurementSessionRequest) =>
    request<MeasurementSessionResponse>({
      method: 'POST',
      url: endpoints.measurementSessions,
      data: payload,
    }),
  list: (params: MeasurementListParams) =>
    request<MeasurementSessionPage>({
      method: 'GET',
      url: endpoints.measurementSessions,
      params,
    }),
  get: (sessionId: string) =>
    request<MeasurementSessionResponse>({
      method: 'GET',
      url: endpoints.measurementSession(sessionId),
    }),
  start: (sessionId: string) =>
    request<MeasurementSessionResponse>({
      method: 'POST',
      url: endpoints.startMeasurement(sessionId),
    }),
  complete: (sessionId: string) =>
    request<MeasurementSessionResponse>({
      method: 'POST',
      url: endpoints.completeMeasurement(sessionId),
    }),
  cancel: (sessionId: string) =>
    request<MeasurementSessionResponse>({
      method: 'POST',
      url: endpoints.cancelMeasurement(sessionId),
    }),
  snapshot: async (sessionId: string) =>
    parseRealtimeValue(
      await request<unknown>({
        method: 'GET',
        url: endpoints.realtimeSnapshot(sessionId),
      }),
    ),
  result: async (sessionId: string): Promise<ResultResponse> => {
    try {
      const response = await apiClient.get<AnalysisResultResponse | AnalysisPendingResponse>(
        endpoints.result(sessionId),
        { validateStatus: (status) => status === 200 || status === 202 },
      );
      return response.status === 202
        ? { kind: 'processing', data: response.data as AnalysisPendingResponse }
        : { kind: 'completed', data: response.data as AnalysisResultResponse };
    } catch (error) {
      throw toApiError(error);
    }
  },
};

export const recommendationApi = {
  get: (code: string) =>
    request<RecommendationDetailResponse>({
      method: 'GET',
      url: endpoints.recommendation(code),
    }),
};
