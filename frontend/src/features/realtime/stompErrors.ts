// 백엔드는 구독 중 토큰이 만료되면 STOMP ERROR 프레임(message:TOKEN_EXPIRED)을 한 번 보내고 연결을 끊는다(DEC-031).
// 만료 토큰의 CONNECT/SUBSCRIBE도 같은 메시지로 거절된다.
export const TOKEN_EXPIRED_CODE = 'TOKEN_EXPIRED';

export interface StompErrorFrameLike {
  headers: Record<string, string | undefined>;
  body?: string;
}

export const isTokenExpiredFrame = (frame: StompErrorFrameLike): boolean => {
  const message = frame.headers.message ?? '';
  const body = frame.body ?? '';
  return message.includes(TOKEN_EXPIRED_CODE) || body.includes(TOKEN_EXPIRED_CODE);
};
