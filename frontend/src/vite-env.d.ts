/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_WS_URL?: string;
  /** 새 측정 폼의 기본 sampleRateHz. 50 또는 100만 유효하며 그 외 값은 50으로 처리한다. */
  readonly VITE_DEFAULT_SAMPLE_RATE_HZ?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
