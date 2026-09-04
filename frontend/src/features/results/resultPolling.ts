import type { ResultResponse } from '../../api/types';

export const RESULT_POLL_INTERVAL_MS = 2_500;

export const shouldPollResult = (result: ResultResponse | undefined): boolean =>
  result === undefined || result.kind === 'processing';
