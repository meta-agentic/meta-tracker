import type { ReactNode, SVGProps } from "react";

/**
 * The SPA's icon set: a handful of 16px stroke glyphs drawn in `currentColor`,
 * so every icon takes its colour from the token its parent sets. Inline SVG
 * rather than an icon font or a package: there are few of them, and none is
 * worth a dependency or a request.
 *
 * Every icon is decorative (`aria-hidden`). Where an icon carries meaning, the
 * text beside it carries the same meaning.
 */

type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Icon({ size = 16, children, ...rest }: IconProps & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  );
}

/** The product mark: two converging strokes, a vector heading somewhere. */
export function LogoMark({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <rect width="24" height="24" rx="6" fill="var(--vec-accent)" />
      <path
        d="M6.5 7.5 12 17l5.5-9.5"
        fill="none"
        stroke="var(--vec-on-accent)"
        strokeWidth={2.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function StoryIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4.5 2.5h7v11L8 11l-3.5 2.5z" />
    </Icon>
  );
}

export function BugIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <circle cx="8" cy="8" r="5.5" />
      <circle cx="8" cy="8" r="1.75" fill="currentColor" />
    </Icon>
  );
}

export function TaskIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect x="2.5" y="2.5" width="11" height="11" rx="2.5" />
      <path d="m5.5 8 1.75 1.75L10.5 6.5" />
    </Icon>
  );
}

export function SpikeIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M6 2.5h4M6.75 2.5v4L3.5 12.25a.8.8 0 0 0 .7 1.25h7.6a.8.8 0 0 0 .7-1.25L9.25 6.5v-4" />
    </Icon>
  );
}

export function EnablerIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M9 1.75 3.75 9h4l-1 5.25L12.25 7h-4z" />
    </Icon>
  );
}

export function GenericTypeIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect x="3" y="3" width="10" height="10" rx="2" />
    </Icon>
  );
}

export function CloseIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="m4 4 8 8M12 4l-8 8" />
    </Icon>
  );
}

export function RetryIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M13 8a5 5 0 1 1-1.5-3.56" />
      <path d="M13 2.5V5h-2.5" />
    </Icon>
  );
}

export function CloudOffIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M5 12.5h6.25a2.75 2.75 0 0 0 .6-5.43A4 4 0 0 0 4.3 6.2 3.2 3.2 0 0 0 5 12.5z" />
      <path d="m2.5 2.5 11 11" />
    </Icon>
  );
}

export function WarningIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M8 2.25 1.75 13.25h12.5z" />
      <path d="M8 6.5v3" />
      <path d="M8 11.5h.01" />
    </Icon>
  );
}

export function InboxIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M2 9.5 3.75 3.5h8.5L14 9.5v3a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" />
      <path d="M2 9.5h3.5l1 1.5h3l1-1.5H14" />
    </Icon>
  );
}

export function BoardIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect x="2" y="2.5" width="12" height="11" rx="2" />
      <path d="M6 2.5v11M10 2.5v11" />
    </Icon>
  );
}

export function TimelineIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M2.5 4h6M5 8h7.5M3.5 12h5" />
    </Icon>
  );
}

/** Staggered bars on a time axis: the roadmap tab. */
export function RoadmapIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M2.5 2.5v11h11" />
      <rect x="5" y="4" width="5" height="2" rx="1" />
      <rect x="7.5" y="8.5" width="5" height="2" rx="1" />
    </Icon>
  );
}

export function LinkIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M6.75 9.25a2.5 2.5 0 0 0 3.54 0l2-2a2.5 2.5 0 0 0-3.54-3.54l-.5.5" />
      <path d="M9.25 6.75a2.5 2.5 0 0 0-3.54 0l-2 2a2.5 2.5 0 0 0 3.54 3.54l.5-.5" />
    </Icon>
  );
}

/** Nested rows: the backlog tab. */
export function TreeIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M2.5 3.5h9" />
      <path d="M4.5 6v6.5H7M4.5 8.5H7" />
      <path d="M9.5 8.5h4M9.5 12.5h4" />
    </Icon>
  );
}

export function ChevronIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="m6 3.5 4.5 4.5L6 12.5" />
    </Icon>
  );
}

export function ListIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M3 4.5h10M3 8h10M3 11.5h6" />
    </Icon>
  );
}
