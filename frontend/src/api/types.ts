import type { components, operations } from './generated/schema';

export type ApiErrorBody = components['schemas']['ApiError'];
export type SignupRequest = components['schemas']['SignupRequest'];
export type SigninRequest = components['schemas']['SigninRequest'];
export type UserSummary = components['schemas']['UserSummary'];
export type AuthTokenResponse = components['schemas']['AuthTokenResponse'];
export type RegisterDeviceRequest = components['schemas']['RegisterDeviceRequest'];
export type DeviceResponse = components['schemas']['DeviceResponse'];
export type DeviceStatus = components['schemas']['DeviceStatus'];
export type FootSide = components['schemas']['FootSide'];
export type SensorPoint = components['schemas']['SensorPoint'];
export type SensorLayoutResponse = components['schemas']['SensorLayoutResponse'];
export type SourceType = components['schemas']['SourceType'];
export type ReceiverUploadState = components['schemas']['ReceiverUploadState'];
export type CreateMeasurementSessionRequest =
  components['schemas']['CreateMeasurementSessionRequest'];
export type SampleRateHz = CreateMeasurementSessionRequest['sampleRateHz'];
export type MeasurementSessionResponse = components['schemas']['MeasurementSessionResponse'];
export type MeasurementSessionPage = components['schemas']['MeasurementSessionPage'];
export type MeasurementHistoryItem = components['schemas']['MeasurementHistoryItem'];
export type MeasurementStatus = components['schemas']['MeasurementStatus'];
export type FootRealtimeData = components['schemas']['FootRealtimeData'];
export type RealtimePressureMessage = components['schemas']['RealtimePressureMessage'];
export type RealtimeQuality = components['schemas']['RealtimeQuality'];
export type QualityLevel = components['schemas']['QualityLevel'];
export type ContactState = components['schemas']['ContactState'];
export type AnalysisPendingResponse = components['schemas']['AnalysisPendingResponse'];
export type AnalysisResultResponse = components['schemas']['AnalysisResultResponse'];
export type DataQualityResult = components['schemas']['DataQualityResult'];
export type PressureDistribution = components['schemas']['PressureDistribution'];
export type SensorSharePct = components['schemas']['SensorSharePct'];
export type RecommendationSummary = components['schemas']['RecommendationSummary'];
export type RecommendationDetailResponse = components['schemas']['RecommendationDetailResponse'];
export type PatternResult = components['schemas']['PatternResult'];
export type PatternSeverity = components['schemas']['PatternSeverity'];
export type ObservationLevel = components['schemas']['ObservationLevel'];
export type ObservationSummaryItem = components['schemas']['ObservationSummaryItem'];
export type ObservationPatternCode = ObservationSummaryItem['code'];
export type MeasurementListQuery = NonNullable<
  operations['listMeasurementSessions']['parameters']['query']
>;

export type ResultResponse =
  | { kind: 'processing'; data: AnalysisPendingResponse }
  | { kind: 'completed'; data: AnalysisResultResponse };
