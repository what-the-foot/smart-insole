import { queryClient } from './queryClient';
import { clearAuthSession, saveAuthResponse } from '../features/auth/authSession';
import { ApiError, apiClient, toApiError } from './client';

describe('API 오류 변환', () => {
  it('계약 오류 본문과 HTTP 상태를 안전한 ApiError로 변환한다', () => {
    const converted = toApiError({
      isAxiosError: true,
      response: {
        status: 422,
        data: {
          code: 'INVALID_DEVICE_SIDE',
          message: '기기 방향을 확인해 주세요.',
          details: { expected: 'LEFT' },
          traceId: 'trace-test',
          timestamp: '2026-09-02T07:30:00Z',
        },
      },
    });

    expect(converted).toBeInstanceOf(ApiError);
    expect(converted).toMatchObject({
      status: 422,
      code: 'INVALID_DEVICE_SIDE',
      message: '기기 방향을 확인해 주세요.',
      traceId: 'trace-test',
    });
  });

  it('응답이 없는 네트워크 실패에는 사용자가 취할 행동을 안내한다', () => {
    const converted = toApiError({ isAxiosError: true, code: 'ERR_NETWORK' });
    expect(converted.status).toBeNull();
    expect(converted.message).toContain('네트워크 상태를 확인');
  });

  it.each([
    ['details 배열', { details: [] }],
    ['traceId 숫자', { traceId: 123 }],
    ['date-time이 아닌 timestamp', { timestamp: '2026-09-02' }],
    ['추가 속성', { unexpected: true }],
  ])('계약을 위반한 오류 본문(%s)을 신뢰하지 않는다', (_case, override) => {
    const converted = toApiError({
      isAxiosError: true,
      response: {
        status: 422,
        data: {
          code: 'SHOULD_NOT_ESCAPE',
          message: '노출하면 안 되는 서버 문자열',
          details: {},
          traceId: 'trace-test',
          timestamp: '2026-09-02T07:30:00Z',
          ...override,
        },
      },
    });

    expect(converted.code).toBe('NETWORK_ERROR');
    expect(converted.message).not.toContain('노출하면 안 되는');
  });

  it('HTTP 401 응답은 인증 저장소와 사용자별 쿼리 캐시를 함께 비운다', async () => {
    saveAuthResponse({
      tokenType: 'Bearer',
      accessToken: 'expired-token',
      expiresInSeconds: 3600,
      user: {
        userId: '7e95630d-6b53-4b1d-96f4-0acc7ab72e91',
        email: 'walker@example.com',
        name: '테스트 사용자',
        createdAt: '2026-09-02T07:00:00Z',
      },
    });
    queryClient.setQueryData(['devices'], [{ serialNumber: 'PRIVATE' }]);

    await expect(
      apiClient.get('/private', {
        adapter: (config) => {
          const unauthorized = Object.assign(new Error('Unauthorized'), {
            isAxiosError: true,
            config,
            response: { status: 401, data: {}, config, headers: {}, statusText: 'Unauthorized' },
          });
          return Promise.reject(unauthorized);
        },
      }),
    ).rejects.toBeInstanceOf(ApiError);

    expect(window.sessionStorage.getItem('smart-insole.auth.v1')).toBeNull();
    expect(queryClient.getQueryData(['devices'])).toBeUndefined();
    clearAuthSession();
  });
});
