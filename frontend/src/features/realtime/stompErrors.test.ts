import { describe, expect, it } from 'vitest';
import { isTokenExpiredFrame } from './stompErrors';

describe('isTokenExpiredFrame', () => {
  it('ERROR 프레임의 message 헤더 또는 본문에서 TOKEN_EXPIRED를 찾는다', () => {
    expect(isTokenExpiredFrame({ headers: { message: 'TOKEN_EXPIRED' }, body: '' })).toBe(true);
    expect(isTokenExpiredFrame({ headers: {}, body: 'TOKEN_EXPIRED: access token expired' })).toBe(
      true,
    );
    expect(isTokenExpiredFrame({ headers: { message: 'Forbidden' }, body: 'not owner' })).toBe(
      false,
    );
    expect(isTokenExpiredFrame({ headers: {} })).toBe(false);
  });
});
