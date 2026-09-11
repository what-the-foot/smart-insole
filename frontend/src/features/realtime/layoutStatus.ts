import type { FetchStatus } from '@tanstack/react-query';
import type { DeviceResponse } from '../../api/types';

/** 히트맵에 센서 배치(layout)가 없을 때의 이유. loading만 '불러오는 중'으로 표시한다. */
export type LayoutStatus = 'loading' | 'error' | 'unavailable';

export interface LayoutStatusInput {
  layout: { isPending: boolean; isError: boolean; fetchStatus: FetchStatus };
  devices: { isPending: boolean; isError: boolean };
  /** 세션의 그 발에 해당하는 기기. 목록에 없으면(삭제·타인 기기) undefined. */
  device: DeviceResponse | undefined;
}

// useSensorLayout은 배치 버전이 없으면 enabled=false라 isPending이면서 fetchStatus 'idle'로 영원히 머문다.
// 그 상태가 '불러오는 중'으로 보이지 않도록 기기 목록·배치 조회 상태를 함께 본다.
export const sensorLayoutStatus = ({
  layout,
  devices,
  device,
}: LayoutStatusInput): LayoutStatus => {
  if (devices.isError || layout.isError) return 'error';
  if (devices.isPending) return 'loading';
  if (device === undefined) return 'unavailable';
  if (layout.isPending && layout.fetchStatus === 'idle') return 'unavailable';
  return 'loading';
};
