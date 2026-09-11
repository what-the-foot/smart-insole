import type { SVGProps } from 'react';

export type IconName =
  | 'activity'
  | 'alert'
  | 'arrow'
  | 'asymmetry'
  | 'battery'
  | 'chart'
  | 'check'
  | 'chevron-down'
  | 'clock'
  | 'device'
  | 'foot'
  | 'forefoot'
  | 'gauge'
  | 'guide'
  | 'hallux'
  | 'help'
  | 'history'
  | 'home'
  | 'lateral'
  | 'link'
  | 'logout'
  | 'medial'
  | 'menu'
  | 'plus'
  | 'rearfoot'
  | 'settings'
  | 'shield'
  | 'sparkles'
  | 'user'
  | 'x';

// 발 모양 윤곽(24px). 관찰 패턴 아이콘은 이 윤곽 위에 강조 영역을 겹친다.
const footOutline = (
  <path d="M9 3.5c2.6 0 4.6 2.2 5.4 5.6.4 1.7 1.6 2.9 2.1 4.4.8 2.5-.3 5.5-2.7 6.7-1.6.8-3.7.7-5.5.1-2.2-.8-3.3-3-3.1-5.4.1-1.6.9-2.9 1.2-4.5C7 7.4 6.6 3.5 9 3.5Z" />
);

const paths: Record<IconName, React.ReactNode> = {
  activity: <path d="M3 12h4l2.2-6 4.1 12 2.2-6H21" />,
  alert: (
    <>
      <path d="M12 3 2.8 19h18.4L12 3Z" />
      <path d="M12 9v4M12 16.5h.01" />
    </>
  ),
  arrow: <path d="m9 18 6-6-6-6" />,
  asymmetry: (
    <>
      <path d="M12 3v18" strokeDasharray="2 3" />
      <path d="M9 6.5c-2.4 0-4 2.5-4 5.5s1.6 6.5 4 6.5" />
      <path d="M15 9c1.8 0 3 1.8 3 4s-1.2 5-3 5" />
    </>
  ),
  battery: (
    <>
      <rect x="2.5" y="7" width="17" height="10" rx="2.5" />
      <path d="M21.5 10.5v3" />
      <path d="M6 10.5v3M9.5 10.5v3" />
    </>
  ),
  chart: (
    <>
      <path d="M4 20V4" />
      <path d="M4 20h16" />
      <path d="M8 16v-5M12 16V8M16 16v-3" />
    </>
  ),
  check: <path d="m5 12 4 4L19 6" />,
  'chevron-down': <path d="m6 9 6 6 6-6" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  device: (
    <>
      <rect x="5" y="2.5" width="14" height="19" rx="4" />
      <path d="M9 6h6M10 18h4" />
    </>
  ),
  foot: footOutline,
  forefoot: (
    <>
      {footOutline}
      <path d="M8.5 6.5c1.5-1.2 3.3-1.2 4.8.1" strokeWidth="2.6" />
    </>
  ),
  gauge: (
    <>
      <path d="M4.5 16.5a8 8 0 1 1 15 0" />
      <path d="m12 15 3.5-5" />
      <circle cx="12" cy="15.5" r="1.2" />
    </>
  ),
  guide: (
    <>
      <path d="M6 4h9l4 4v12H6Z" />
      <path d="M15 4v4h4" />
      <path d="M9 13h6M9 17h4" />
    </>
  ),
  hallux: (
    <>
      {footOutline}
      <circle cx="13.5" cy="6.8" r="1.6" />
      <path d="M16.5 4.5l1.5-1.5" />
    </>
  ),
  help: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M9.6 9.5a2.5 2.5 0 1 1 3.6 2.3c-.8.5-1.2 1-1.2 1.9" />
      <path d="M12 17h.01" />
    </>
  ),
  history: (
    <>
      <path d="M4 4v5h5" />
      <path d="M4.8 9a8 8 0 1 1-.2 6" />
      <path d="M12 8v5l3 2" />
    </>
  ),
  home: (
    <>
      <path d="m3 11 9-8 9 8" />
      <path d="M5 10v10h14V10M9 20v-6h6v6" />
    </>
  ),
  lateral: (
    <>
      {footOutline}
      <path d="M15.5 10.5c1 1.6 1.4 3.6 1 5.5" strokeWidth="2.6" />
    </>
  ),
  link: (
    <>
      <path d="M10 14a4 4 0 0 0 5.7 0l2.8-2.8a4 4 0 0 0-5.7-5.7l-1.2 1.2" />
      <path d="M14 10a4 4 0 0 0-5.7 0l-2.8 2.8a4 4 0 0 0 5.7 5.7l1.2-1.2" />
    </>
  ),
  logout: (
    <>
      <path d="M10 4H5v16h5" />
      <path d="M14 8l4 4-4 4M8 12h10" />
    </>
  ),
  medial: (
    <>
      {footOutline}
      <path d="M7 10.5c-.8 1.8-1 3.7-.6 5.5" strokeWidth="2.6" />
    </>
  ),
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  plus: <path d="M12 5v14M5 12h14" />,
  rearfoot: (
    <>
      {footOutline}
      <path d="M9.5 19.5c1.4.6 3 .6 4.3-.1" strokeWidth="2.6" />
    </>
  ),
  settings: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.5 1.5M17.2 17.2l1.5 1.5M5.3 18.7l1.5-1.5M17.2 6.8l1.5-1.5" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3 5 6v5c0 4.6 2.8 8.2 7 10 4.2-1.8 7-5.4 7-10V6l-7-3Z" />
      <path d="m9 12 2 2 4-5" />
    </>
  ),
  sparkles: (
    <>
      <path d="m12 3 1.4 3.6L17 8l-3.6 1.4L12 13l-1.4-3.6L7 8l3.6-1.4L12 3Z" />
      <path d="m5 14 .8 2.2L8 17l-2.2.8L5 20l-.8-2.2L2 17l2.2-.8L5 14ZM19 13l.7 1.3L21 15l-1.3.7L19 17l-.7-1.3L17 15l1.3-.7L19 13Z" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0 1 16 0" />
    </>
  ),
  x: <path d="m6 6 12 12M18 6 6 18" />,
};

export function Icon({ name, ...props }: { name: IconName } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      height="20"
      viewBox="0 0 24 24"
      width="20"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.8"
      {...props}
    >
      {paths[name]}
    </svg>
  );
}
