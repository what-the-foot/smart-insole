import { describe, expect, it } from 'vitest';
import { isBcryptLengthSupported, utf8ByteLength } from './validation';

describe('비밀번호 UTF-8 byte 제한', () => {
  it('ASCII 72 byte는 허용하고 73 byte는 거절한다', () => {
    expect(isBcryptLengthSupported('a'.repeat(72))).toBe(true);
    expect(isBcryptLengthSupported('a'.repeat(73))).toBe(false);
  });

  it('다국어 문자는 문자 수가 아니라 UTF-8 byte로 계산한다', () => {
    expect(utf8ByteLength('가'.repeat(24))).toBe(72);
    expect(isBcryptLengthSupported('가'.repeat(25))).toBe(false);
  });
});
