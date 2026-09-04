export const formatDateTime = (value?: string | null): string => {
  if (!value) return '기록 없음';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '알 수 없는 시각';
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
};

export const formatDuration = (milliseconds: number): string => {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return [hours, minutes, seconds]
    .filter((_, index) => hours > 0 || index > 0)
    .map((part) => part.toString().padStart(2, '0'))
    .join(':');
};

export const formatPercent = (ratio: number): string =>
  new Intl.NumberFormat('ko-KR', { style: 'percent', maximumFractionDigits: 1 }).format(ratio);

export const formatNumber = (value: number, maximumFractionDigits = 1): string =>
  new Intl.NumberFormat('ko-KR', { maximumFractionDigits }).format(value);
