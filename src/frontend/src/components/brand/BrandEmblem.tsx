// Original OMNIS ui.tsx BrandEmblem, preserved vector geometry.
export function BrandEmblem({ width = 34 }: { width?: number }) {
  const wing = <path d="M15.5 11 4 7.5M15.5 14 6 13M16 17 9 18.5" />
  return <svg viewBox="0 0 48 28" width={width} height={width * 28 / 48} aria-hidden fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
    {wing}
    <g transform="translate(48 0) scale(-1 1)">{wing}</g>
    <circle cx="24" cy="14" r="6.3" strokeWidth="1.8" />
    <path d="M24 10.4 24.85 12.83 27.42 12.89 25.38 14.45 26.12 16.91 24 15.45 21.88 16.91 22.62 14.45 20.58 12.89 23.15 12.83Z" fill="currentColor" stroke="none" />
  </svg>
}
