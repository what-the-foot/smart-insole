import { DEFAULT_SAMPLE_RATE_HZ_SETTING } from '../../api/config';
import type { SampleRateHz } from '../../api/types';

// 세션 sampleRateHz는 전송률의 단일 출처(DEC-028). 계약 enum은 50(BLE 전송률, 측정 100Hz 분주)·100.
export const SAMPLE_RATE_OPTIONS: readonly SampleRateHz[] = [50, 100];
export const FALLBACK_SAMPLE_RATE_HZ: SampleRateHz = 50;

export const sampleRateLabels: Record<SampleRateHz, { title: string; description: string }> = {
  50: { title: '50Hz', description: '실기기 기본값. BLE 전송률(측정 100Hz를 2:1 분주).' },
  100: { title: '100Hz', description: '전송률 100Hz. MTU·링크 여유가 확인된 경우에만 사용.' },
};

export const isSampleRateHz = (value: unknown): value is SampleRateHz =>
  SAMPLE_RATE_OPTIONS.some((option) => option === value);

// VITE_DEFAULT_SAMPLE_RATE_HZ가 50/100이 아니면 UI 기본값 50으로 되돌린다.
export const resolveDefaultSampleRateHz = (setting: string | undefined): SampleRateHz => {
  const parsed = setting === undefined || setting.trim() === '' ? Number.NaN : Number(setting);
  return isSampleRateHz(parsed) ? parsed : FALLBACK_SAMPLE_RATE_HZ;
};

export const DEFAULT_SAMPLE_RATE_HZ: SampleRateHz = resolveDefaultSampleRateHz(
  DEFAULT_SAMPLE_RATE_HZ_SETTING,
);
