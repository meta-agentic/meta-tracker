import type { ComponentType, SVGProps } from "react";
import {
  BugIcon,
  ChoiceKindIcon,
  DateKindIcon,
  DeliverableIcon,
  EnablerIcon,
  EpicIcon,
  FlagIcon,
  MarkdownKindIcon,
  MilestoneIcon,
  NumberKindIcon,
  PriorityHighIcon,
  PriorityLowIcon,
  PriorityMediumIcon,
  ProjectIcon,
  ScaleKindIcon,
  SpikeIcon,
  StoryIcon,
  TaskIcon,
  TextKindIcon,
  WarningIcon,
} from "../components/icons";
import type { FieldSpec } from "./definition";

export type Glyph = ComponentType<SVGProps<SVGSVGElement> & { size?: number }>;

/**
 * The icons a template may name on an item type or a choice option. The names
 * are the template schema's `icon` enum (vectis-domain), which the resolver
 * enforces; a test holds this map to the same names.
 */
export const FIELD_ICONS: Record<string, Glyph> = {
  story: StoryIcon,
  bug: BugIcon,
  task: TaskIcon,
  epic: EpicIcon,
  spike: SpikeIcon,
  enabler: EnablerIcon,
  project: ProjectIcon,
  milestone: MilestoneIcon,
  deliverable: DeliverableIcon,
  high: PriorityHighIcon,
  medium: PriorityMediumIcon,
  low: PriorityLowIcon,
  flag: FlagIcon,
  warning: WarningIcon,
};

/**
 * What a form field holds, as its glyph says it: the field's kind, with a
 * number on a point scale apart from a plain number, because it is drawn as a
 * choice of its points. The kind comes from the configuration; no template
 * names or changes these glyphs.
 */
export type FieldKind = FieldSpec["kind"] | "scale";

/** The glyph each field kind has beside its label. */
export const KIND_ICONS: Record<FieldKind, Glyph> = {
  text: TextKindIcon,
  markdown: MarkdownKindIcon,
  number: NumberKindIcon,
  scale: ScaleKindIcon,
  date: DateKindIcon,
  choice: ChoiceKindIcon,
};

/** A field's kind: a choice sent as a number is a number on a point scale. */
export function kindOf(field: Pick<FieldSpec, "kind" | "numeric">): FieldKind {
  return field.kind === "choice" && field.numeric ? "scale" : field.kind;
}
