import { useQuery } from '@tanstack/react-query';
import { deviceApi, measurementApi, recommendationApi, type MeasurementListParams } from './services';

export const queryKeys = {
  devices: ['devices'] as const,
  sensorLayout: (version: string) => ['sensor-layout', version] as const,
  measurements: (params: MeasurementListParams) => ['measurements', params] as const,
  measurement: (sessionId: string) => ['measurement', sessionId] as const,
  snapshot: (sessionId: string) => ['realtime-snapshot', sessionId] as const,
  result: (sessionId: string) => ['measurement-result', sessionId] as const,
  recommendation: (code: string) => ['recommendation', code] as const,
};

export const useDevices = () =>
  useQuery({ queryKey: queryKeys.devices, queryFn: deviceApi.list, staleTime: 15_000 });

export const useSensorLayout = (version: string | undefined) =>
  useQuery({
    queryKey: queryKeys.sensorLayout(version ?? 'missing'),
    queryFn: () => deviceApi.getLayout(version ?? ''),
    enabled: Boolean(version),
    staleTime: Number.POSITIVE_INFINITY,
  });

export const useMeasurements = (params: MeasurementListParams) =>
  useQuery({
    queryKey: queryKeys.measurements(params),
    queryFn: () => measurementApi.list(params),
    placeholderData: (previous) => previous,
  });

export const useMeasurement = (sessionId: string | undefined) =>
  useQuery({
    queryKey: queryKeys.measurement(sessionId ?? 'missing'),
    queryFn: () => measurementApi.get(sessionId ?? ''),
    enabled: Boolean(sessionId),
  });

export const useRecommendation = (code: string | undefined) =>
  useQuery({
    queryKey: queryKeys.recommendation(code ?? 'missing'),
    queryFn: () => recommendationApi.get(code ?? ''),
    enabled: Boolean(code),
    staleTime: 5 * 60_000,
  });
