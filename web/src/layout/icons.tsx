import type { SVGProps } from 'react'

// Navigation glyphs, drawn to match the ui kit's icon style.
type IconProps = SVGProps<SVGSVGElement>

function Svg({ children, ...props }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="1em"
      height="1em"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {children}
    </svg>
  )
}

export const HomeIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 10.5 12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5H15v-5.5h-6v5.5H5.5A1.5 1.5 0 0 1 4 19Z" />
  </Svg>
)
export const PhoneIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="6.5" y="2.75" width="11" height="18.5" rx="2.75" />
    <path d="M10.5 18h3" />
  </Svg>
)
export const KeyIcon = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="8" cy="15.5" r="4" />
    <path d="m10.9 12.6 8.6-8.6M16.5 7l2.5 2.5M14 9.5l2 2" />
  </Svg>
)
export const LogOutIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M14 4.5h3.5A1.5 1.5 0 0 1 19 6v12a1.5 1.5 0 0 1-1.5 1.5H14" />
    <path d="M10 8l-4 4 4 4M6 12h9" />
  </Svg>
)
export const ChevronDownIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="m7 10 5 5 5-5" />
  </Svg>
)
export const ArrowRightIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M5 12h14M13 6l6 6-6 6" />
  </Svg>
)
export const MailIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3" y="5" width="18" height="14" rx="3" />
    <path d="m4 7.5 8 5.5 8-5.5" />
  </Svg>
)
export const SendIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M20.5 3.5 10 14M20.5 3.5 14 20.5l-4-6.5-6.5-4Z" />
  </Svg>
)
