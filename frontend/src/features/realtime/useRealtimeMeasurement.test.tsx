import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { measurementApi } from '../../api/services';
import type { RealtimePressureMessage } from '../../api/types';
import { AuthProvider } from '../auth/AuthContext';
import { saveAuthResponse } from '../auth/authSession';
import { useRealtimeMeasurement } from './useRealtimeMeasurement';

const stompMock = vi.hoisted(() => ({
  deactivate: vi.fn(),
  unsubscribe: vi.fn(),
  subscriptions: [] as string[],
  actions: [] as string[],
  callbacks: [] as ((message: { body: string }) => void)[],
}));

vi.mock('@stomp/stompjs', () => ({
  ReconnectionTimeMode: { EXPONENTIAL: 'EXPONENTIAL' },
  Client: class {
    connected = true;
    active = true;
    onConnect = (): void => undefined;
    onWebSocketClose = (): void => undefined;
    onWebSocketError = (): void => undefined;
    onStompError = (): void => undefined;

    activate(): void {
      queueMicrotask(() => this.onConnect());
    }

    deactivate(): Promise<void> {
      this.active = false;
      this.connected = false;
      stompMock.deactivate();
      return Promise.resolve();
    }

    subscribe(destination: string, callback: (message: { body: string }) => void): { unsubscribe: () => void } {
      stompMock.subscriptions.push(destination);
      stompMock.actions.push('subscribe');
      stompMock.callbacks.push(callback);
      return { unsubscribe: stompMock.unsubscribe };
    }
  },
}));

const sessionOne = '5803f871-9fca-4a7f-a2c7-9b567a92a6cf';
const sessionTwo = 'd36b4e7d-02c4-4058-b03e-01a3b6df52d8';

const snapshot = (
  sessionId: string,
  leftPressure: number,
  withRight: boolean,
): RealtimePressureMessage => ({
  schemaVersion: '1.0',
  sessionId,
  serverTime: '2026-09-02T07:11:10.500Z',
  elapsedTimeMs: 10_500,
  status: 'MEASURING',
  left: {
    connected: true,
    lastSequence: 111,
    deviceTimeMs: 1110,
    sensorValues: [12, 24, 51, 80, 61, 30],
    totalPressure: leftPressure,
    cop: { x: 0.42, y: 0.73 },
    contactState: 'CONTACT',
    lastReceivedAt: '2026-09-02T07:11:10.490Z',
  },
  right: withRight
    ? {
        connected: true,
        lastSequence: 112,
        deviceTimeMs: 1120,
        sensorValues: [10, 20, 30, 40, 50, 60],
        totalPressure: 345,
        cop: { x: 0.52, y: 0.63 },
        contactState: 'CONTACT',
        lastReceivedAt: '2026-09-02T07:11:10.492Z',
      }
    : null,
  quality: { score: 92, level: 'GOOD', flags: [] },
});

describe('useRealtimeMeasurement', () => {
  beforeEach(() => {
    stompMock.actions.length = 0;
    stompMock.callbacks.length = 0;
    stompMock.subscriptions.length = 0;
  });

  it('세션 ID 변경 시 이전 발 데이터와 구독을 정리한다', async () => {
    saveAuthResponse({
      tokenType: 'Bearer',
      accessToken: 'test-token',
      expiresInSeconds: 3600,
      user: {
        userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
        email: 'walker@example.com',
        name: '테스트 사용자',
        createdAt: '2026-09-02T07:00:00Z',
      },
    });
    vi.spyOn(measurementApi, 'snapshot').mockImplementation((sessionId) => {
      stompMock.actions.push('snapshot');
      return Promise.resolve(
        sessionId === sessionOne ? snapshot(sessionOne, 258, true) : snapshot(sessionTwo, 222, false),
      );
    });
    const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

    const { result, rerender, unmount } = renderHook(
      ({ sessionId }) => useRealtimeMeasurement(sessionId, true),
      { initialProps: { sessionId: sessionOne }, wrapper },
    );
    await waitFor(() => expect(result.current.message?.sessionId).toBe(sessionOne));
    expect(result.current.right?.totalPressure).toBe(345);

    rerender({ sessionId: sessionTwo });
    expect(result.current.message).toBeNull();
    await waitFor(() => expect(result.current.message?.sessionId).toBe(sessionTwo));
    expect(result.current.left?.totalPressure).toBe(222);
    expect(result.current.right).toBeNull();
    expect(stompMock.unsubscribe).toHaveBeenCalledTimes(1);
    expect(stompMock.deactivate).toHaveBeenCalledTimes(1);

    unmount();
    expect(stompMock.unsubscribe).toHaveBeenCalledTimes(2);
    expect(stompMock.deactivate).toHaveBeenCalledTimes(2);
  });

  it('먼저 구독한 뒤 snapshot을 복구하고 그 사이 도착한 최신 메시지를 되돌리지 않는다', async () => {
    saveAuthResponse({
      tokenType: 'Bearer',
      accessToken: 'test-token',
      expiresInSeconds: 3600,
      user: {
        userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
        email: 'walker@example.com',
        name: '테스트 사용자',
        createdAt: '2026-09-02T07:00:00Z',
      },
    });
    let resolveSnapshot: ((value: RealtimePressureMessage) => void) | undefined;
    vi.spyOn(measurementApi, 'snapshot').mockImplementation(() => {
      stompMock.actions.push('snapshot');
      return new Promise((resolve) => { resolveSnapshot = resolve; });
    });
    const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
    const { result } = renderHook(() => useRealtimeMeasurement(sessionOne, true), { wrapper });

    await waitFor(() => expect(stompMock.callbacks).toHaveLength(1));
    expect(stompMock.actions.slice(0, 2)).toEqual(['subscribe', 'snapshot']);
    const live = snapshot(sessionOne, 333, true);
    if (!live.left) throw new Error('left fixture is required');
    live.left.lastSequence = 222;
    live.left.deviceTimeMs = 2220;
    act(() => stompMock.callbacks[0]?.({ body: JSON.stringify(live) }));
    await act(async () => {
      resolveSnapshot?.(snapshot(sessionOne, 111, true));
      await Promise.resolve();
    });

    await waitFor(() => expect(result.current.left?.lastSequence).toBe(222));
    expect(result.current.left?.totalPressure).toBe(333);
  });
});
