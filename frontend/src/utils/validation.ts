export const MAX_BCRYPT_UTF8_BYTES = 72;

export const utf8ByteLength = (value: string): number => new TextEncoder().encode(value).length;

export const isBcryptLengthSupported = (value: string): boolean =>
  utf8ByteLength(value) <= MAX_BCRYPT_UTF8_BYTES;
