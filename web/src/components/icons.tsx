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

/** A written page with a folded corner and three lines of text. */
export function StoryIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M9.5 1.75H5a1.5 1.5 0 0 0-1.5 1.5v9.5A1.5 1.5 0 0 0 5 14.25h6a1.5 1.5 0 0 0 1.5-1.5v-8z" />
      <path d="M9.5 1.75v3h3M6 6.75h4M6 9h4M6 11.25h2.5" />
    </Icon>
  );
}

/**
 * A beetle, as the PO drew it: a D-shaped body, flat at the top and round at the
 * bottom, split down the middle; a semicircular head on the flat side; two
 * antennae from the head; three legs a side.
 */
export function BugIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4 7.25h8a4 5.75 0 0 1-8 0zM8 7.25V13" />
      <path d="M6 7.25a2 2 0 0 1 4 0M6.6 5.85 5.25 3.5M9.4 5.85l1.35-2.35" />
      <path d="M4.15 8.75 2.25 7.75M4.8 10.75H2.5M5.6 12.1 3.75 13.75M11.85 8.75l1.9-1M11.2 10.75h2.3M10.4 12.1l1.85 1.65" />
    </Icon>
  );
}

/** A bookmark. */
export function TaskIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4.5 2.5h7v11L8 11l-3.5 2.5z" />
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

/** A circling arrow: the sprint tab. */
export function SprintIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M13 8a5 5 0 1 1-1.5-3.5" />
      <path d="M13 2.5v3h-3" />
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

export function SearchIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <circle cx="7" cy="7" r="4" />
      <path d="m10 10 3.5 3.5" />
    </Icon>
  );
}

/* ---------- Icons a template may name (VEC-70): item types and choice options ---------- */

export function EpicIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="m8 2 5.5 3L8 8 2.5 5z" />
      <path d="m2.5 8 5.5 3 5.5-3M2.5 11 8 14l5.5-3" />
    </Icon>
  );
}

export function ProjectIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M2.5 4.5a1 1 0 0 1 1-1h3l1.5 1.5h4.5a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z" />
    </Icon>
  );
}

/** A flag with a check in its cloth: a square cloth, where the plain flag's is notched. */
export function MilestoneIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M3 14.25V2.25M3 2.75h9a1.5 1.5 0 0 1 1.5 1.5v4.5a1.5 1.5 0 0 1-1.5 1.5H3" />
      <path d="m5.75 6.5 1.5 1.5 3-3" />
    </Icon>
  );
}

export function DeliverableIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M2.5 5 8 2.5 13.5 5v6L8 13.5 2.5 11z" />
      <path d="M2.5 5 8 7.5 13.5 5M8 7.5v6" />
    </Icon>
  );
}

export function PriorityHighIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="m4 9.5 4-4 4 4M4 13l4-4 4 4" />
    </Icon>
  );
}

export function PriorityMediumIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M3.5 6.25h9M3.5 9.75h9" />
    </Icon>
  );
}

export function PriorityLowIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="m4 6.5 4 4 4-4" />
    </Icon>
  );
}

export function FlagIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M3.5 14V2.5M3.5 3h8l-1.75 3L11.5 9h-8" />
    </Icon>
  );
}

/* ---------- Field kinds (VEC-70): drawn beside a field's label in the forms ---------- */

/** A letter T: one line of text. */
export function TextKindIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M3.5 3.5h9M8 3.5v9M6.25 12.5h3.5" />
    </Icon>
  );
}

/** The Markdown mark, an M and a down arrow in a frame: text over several lines, shown as Markdown. */
export function MarkdownKindIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect x="1.5" y="3.5" width="13" height="9" rx="2" />
      <path d="M4 10V6l2 2 2-2v4M11 6v4M9.5 8.5 11 10l1.5-1.5" />
    </Icon>
  );
}

/** A number sign. */
export function NumberKindIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M6.5 2.5 5 13.5M11 2.5 9.5 13.5M3 5.75h10.5M2.5 10.25H13" />
    </Icon>
  );
}

/** Rising bars: a number on a point scale. */
export function ScaleKindIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M3 13v-2M6.33 13V9M9.67 13V6.5M13 13V3.5" />
    </Icon>
  );
}

/** A calendar page. */
export function DateKindIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect x="2.5" y="3.5" width="11" height="10" rx="2" />
      <path d="M2.5 7h11M5.5 2v3M10.5 2v3" />
    </Icon>
  );
}

/** Two options, the first chosen: one of a list. */
export function ChoiceKindIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <circle cx="4.5" cy="5" r="2" />
      <circle cx="4.5" cy="5" r="0.75" fill="currentColor" stroke="none" />
      <circle cx="4.5" cy="11" r="2" />
      <path d="M8.5 5h5M8.5 11h5" />
    </Icon>
  );
}
