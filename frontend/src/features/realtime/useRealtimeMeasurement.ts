import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, ReconnectionTimeMode, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { measurementApi } from '../../api/services';
import { endpoints, WS_URL } from '../../api/config';
import type { FootRealtimeData, RealtimePressureMessage } from '../../api/types';
import { useAuth } from '../auth/AuthContext';
import { parseRealtimeMessage } from './realtimeSchema';
import { isTokenExpiredFrame } from './stompErrors';

export type RealtimeConnectionStatus =
  | 'IDLE'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'RECONNECTING'
  | 'DISCONNECTED'
  /** 서버 ERROR 프레임 TOKEN_EXPIRED. 재로그인으로 session이 바뀌면 새 토큰으로 재구독한다. */
  | 'AUTH_EXPIRED'
  | 'ERROR';

export const AUTH_EXPIRED_MESSAGE =
  '로그인 세션이 만료되어 실시간 연결이 끊겼습니다. 다시 로그인하면 같은 세션을 새 토큰으로 다시 구독합니다.';

type PresentFootData = NonNullable<FootRealtimeData>;

export interface RealtimeState {
  connectionStatus: RealtimeConnectionStatus;
  message: RealtimePressureMessage | null;
  left: PresentFootData | null;
  right: PresentFootData | null;
  leftDisconnected: boolean;
  rightDisconnected: boolean;
  /** 세션 동안 관찰된 발별 최대 센서 신호(0~100 상대값). sessionId가 바뀔 때만 초기화된다. */
  leftPeak: number | null;
  rightPeak: number | null;
  dataStale: boolean;
  error: string | null;
}

interface SessionPeaks {
  sessionId: string;
  left: number | null;
  right: number | null;
}

const maxSensorValue = (foot: PresentFootData): number =>
  foot.sensorValues.reduce((max, value) => (value > max ? value : max), 0);

const higher = (current: number | null, candidate: number): number =>
  current === null || candidate > current ? candidate : current;

export function useRealtimeMeasurement(sessionId: string, enabled: boolean): RealtimeState {
  const { session } = useAuth();
  const [connectionStatus, setConnectionStatus] = useState<RealtimeConnectionStatus>('IDLE');
  const [message, setMessage] = useState<RealtimePressureMessage | null>(null);
  const [left, setLeft] = useState<PresentFootData | null>(null);
  const [right, setRight] = useState<PresentFootData | null>(null);
  // 최대 신호는 sessionId 기준으로만 유지한다. 토큰 교체로 연결 effect가 다시 실행되어도 지워지지 않는다(FE-5).
  const [peaks, setPeaks] = useState<SessionPeaks>({ sessionId, left: null, right: null });
  const [dataStale, setDataStale] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const lastMessageAt = useRef<number | null>(null);

  const resetRealtimeState = useCallback(() => {
    setMessage(null);
    setLeft(null);
    setRight(null);
    setDataStale(false);
    setError(null);
    lastMessageAt.current = null;
  }, []);

  const acceptMessage = useCallback((next: RealtimePressureMessage) => {
    if (next.sessionId !== sessionId) {
      setError('현재 측정과 다른 실시간 메시지를 차단했습니다.');
      return;
    }
    setMessage(next);
    if (next.left) setLeft(next.left);
    if (next.right) setRight(next.right);
    setPeaks((previous) => {
      const base: SessionPeaks =
        previous.sessionId === sessionId ? previous : { sessionId, left: null, right: null };
      return {
        sessionId,
        left: next.left ? higher(base.left, maxSensorValue(next.left)) : base.left,
        right: next.right ? higher(base.right, maxSensorValue(next.right)) : base.right,
      };
    });
    lastMessageAt.current = Date.now();
    setDataStale(false);
    setError(null);
  }, [sessionId]);

  useEffect(() => {
    if (!enabled || !session) {
      resetRealtimeState();
      setConnectionStatus('IDLE');
      return;
    }

    const lifecycle = { active: true };
    let subscription: StompSubscription | null = null;
    let connectedBefore = false;
    const snapshotGuard = new Set<'received'>();
    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${session.accessToken}` },
      connectionTimeout: 8_000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      reconnectDelay: 2_000,
      maxReconnectDelay: 15_000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      debug: () => undefined,
    });

    resetRealtimeState();
    setConnectionStatus('CONNECTING');

    const handleFrame = (frame: IMessage): void => {
      if (!lifecycle.active) return;
      try {
        const parsed = parseRealtimeMessage(frame.body);
        snapshotGuard.add('received');
        acceptMessage(parsed);
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : '실시간 메시지를 처리하지 못했습니다.');
      }
    };

    client.onConnect = () => {
      if (!lifecycle.active) return;
      const reconnecting = connectedBefore;
      connectedBefore = true;
      if (reconnecting) setConnectionStatus('RECONNECTING');
      void (async () => {
        subscription?.unsubscribe();
        snapshotGuard.delete('received');
        try {
          subscription = client.subscribe(endpoints.pressureTopic(sessionId), handleFrame);
        } catch {
          if (lifecycle.active) {
            setConnectionStatus('ERROR');
            setError('실시간 구독을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.');
          }
          return;
        }
        try {
          const snapshot = await measurementApi.snapshot(sessionId);
          if (lifecycle.active && !snapshotGuard.has('received')) acceptMessage(snapshot);
        } catch {
          if (lifecycle.active && reconnecting) {
            setError('최신 상태를 복구하지 못했습니다. 새 데이터를 기다리고 있습니다.');
          }
        }
        if (!lifecycle.active || !client.connected) return;
        setConnectionStatus('CONNECTED');
      })();
    };
    client.onWebSocketClose = () => {
      if (!lifecycle.active) return;
      // TOKEN_EXPIRED로 멈춘 뒤 이어지는 소켓 종료는 AUTH_EXPIRED 상태를 덮어쓰지 않는다.
      setConnectionStatus((current) =>
        current === 'AUTH_EXPIRED' ? current : client.active ? 'RECONNECTING' : 'DISCONNECTED',
      );
    };
    client.onWebSocketError = () => {
      if (lifecycle.active) setError('실시간 연결에 문제가 있습니다. 자동으로 다시 연결합니다.');
    };
    client.onStompError = (frame) => {
      if (!lifecycle.active) return;
      if (isTokenExpiredFrame(frame)) {
        // 만료 토큰으로 자동 재연결해 봐야 같은 ERROR만 반복되므로 이 클라이언트는 멈춘다.
        // 재로그인으로 session(connectHeaders 토큰)이 바뀌면 이 effect가 다시 실행되어 재구독한다.
        setConnectionStatus('AUTH_EXPIRED');
        setError(AUTH_EXPIRED_MESSAGE);
        void client.deactivate();
        return;
      }
      setConnectionStatus('ERROR');
      setError('실시간 구독을 시작하지 못했습니다. 로그인과 세션 상태를 확인해 주세요.');
    };

    client.activate();
    const staleTimer = window.setInterval(() => {
      if (lastMessageAt.current !== null && Date.now() - lastMessageAt.current > 3_000) {
        setDataStale(true);
      }
    }, 1_000);

    return () => {
      lifecycle.active = false;
      window.clearInterval(staleTimer);
      subscription?.unsubscribe();
      void client.deactivate();
    };
  }, [acceptMessage, enabled, resetRealtimeState, session, sessionId]);

  const hasCurrentMessage = enabled && message?.sessionId === sessionId;
  const currentMessage = hasCurrentMessage ? message : null;
  const currentLeft = hasCurrentMessage ? left : null;
  const currentRight = hasCurrentMessage ? right : null;
  const currentPeaks = enabled && peaks.sessionId === sessionId ? peaks : null;

  return {
    connectionStatus: enabled ? connectionStatus : 'IDLE',
    message: currentMessage,
    left: currentLeft,
    right: currentRight,
    leftDisconnected:
      currentMessage?.left === null || currentMessage?.left?.connected === false,
    rightDisconnected:
      currentMessage?.right === null || currentMessage?.right?.connected === false,
    leftPeak: currentPeaks?.left ?? null,
    rightPeak: currentPeaks?.right ?? null,
    dataStale: hasCurrentMessage && dataStale,
    error: enabled ? error : null,
  };
}
