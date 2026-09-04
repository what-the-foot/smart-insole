// navigator.clipboard는 보안 컨텍스트(https/localhost)에서만 제공된다.
// 사설망 http로 접근하는 개발 환경을 위해 execCommand('copy') 폴백을 둔다.
const copyWithExecCommand = (text: string): boolean => {
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  try {
    // execCommand는 폐기 예정이지만 clipboard API가 없는(비보안 컨텍스트) 환경의 마지막 수단이다.
    // eslint-disable-next-line @typescript-eslint/no-deprecated
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    textarea.remove();
  }
};

export const copyTextToClipboard = async (text: string): Promise<boolean> => {
  // 타입 정의는 항상 존재한다고 보지만 http 컨텍스트에서는 undefined다.
  const clipboard: Clipboard | undefined = (navigator as Partial<Navigator>).clipboard;
  if (clipboard) {
    try {
      await clipboard.writeText(text);
      return true;
    } catch {
      // 권한 거부 등 — 폴백으로 진행
    }
  }
  return copyWithExecCommand(text);
};
