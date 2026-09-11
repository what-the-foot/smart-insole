import { useCallback, useEffect, useState } from 'react';
import type { SampleRateHz } from '../api/types';
import { DEFAULT_SAMPLE_RATE_HZ, isSampleRateHz } from '../features/measurement/sampleRates';

// 프론트 전용 환경 설정(v1). 서버 프로필 API가 없어 브라우저 localStorage에만 저장한다.
export const PREFERENCES_STORAGE_KEY = 'smart-insole.preferences.v1';
const PREFERENCES_EVENT = 'smart-insole:preferences-change';

export type HeatmapMode = 'continuous' | 'points';

export interface Preferences {
  defaultSampleRateHz: SampleRateHz;
  heatmapMode: HeatmapMode;
  reduceMotion: boolean;
}

// 저장된 설정이 없으면 기존 env 기본값(VITE_DEFAULT_SAMPLE_RATE_HZ → 50)을 그대로 따른다.
export const defaultPreferences: Preferences = {
  defaultSampleRateHz: DEFAULT_SAMPLE_RATE_HZ,
  heatmapMode: 'continuous',
  reduceMotion: false,
};

export const heatmapModeLabels: Record<HeatmapMode, { title: string; description: string }> = {
  continuous: { title: '연속', description: '센서 사이를 보간한 연속 색상으로 표시합니다.' },
  points: { title: '센서 점', description: '센서 위치마다 원과 값을 표시합니다.' },
};

const isHeatmapMode = (value: unknown): value is HeatmapMode =>
  value === 'continuous' || value === 'points';

const sanitize = (value: unknown): Preferences => {
  if (typeof value !== 'object' || value === null) return defaultPreferences;
  const candidate = value as Record<string, unknown>;
  return {
    defaultSampleRateHz: isSampleRateHz(candidate.defaultSampleRateHz)
      ? candidate.defaultSampleRateHz
      : defaultPreferences.defaultSampleRateHz,
    heatmapMode: isHeatmapMode(candidate.heatmapMode)
      ? candidate.heatmapMode
      : defaultPreferences.heatmapMode,
    reduceMotion:
      typeof candidate.reduceMotion === 'boolean'
        ? candidate.reduceMotion
        : defaultPreferences.reduceMotion,
  };
};

export const readPreferences = (): Preferences => {
  try {
    const serialized = window.localStorage.getItem(PREFERENCES_STORAGE_KEY);
    if (!serialized) return defaultPreferences;
    return sanitize(JSON.parse(serialized));
  } catch {
    return defaultPreferences;
  }
};

export const savePreferences = (next: Preferences): void => {
  try {
    window.localStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(next));
  } catch {
    // 저장 공간을 쓸 수 없는 브라우저(사생활 보호 모드 등)에서는 현재 탭에서만 유지된다.
  }
  window.dispatchEvent(new Event(PREFERENCES_EVENT));
};

// '애니메이션 줄이기'는 CSS [data-reduce-motion="true"] 선택자로 적용한다(global.css shell 블록).
export const applyMotionPreference = (preferences: Preferences): void => {
  document.documentElement.dataset.reduceMotion = preferences.reduceMotion ? 'true' : 'false';
};

export function usePreferences(): [Preferences, (patch: Partial<Preferences>) => void] {
  const [preferences, setPreferences] = useState<Preferences>(() => readPreferences());

  useEffect(() => {
    const listener = () => setPreferences(readPreferences());
    window.addEventListener(PREFERENCES_EVENT, listener);
    window.addEventListener('storage', listener);
    return () => {
      window.removeEventListener(PREFERENCES_EVENT, listener);
      window.removeEventListener('storage', listener);
    };
  }, []);

  const update = useCallback((patch: Partial<Preferences>) => {
    const next = { ...readPreferences(), ...patch };
    savePreferences(next);
    applyMotionPreference(next);
  }, []);

  return [preferences, update];
}
