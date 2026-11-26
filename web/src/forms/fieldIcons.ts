import type { ComponentType, SVGProps } from "react";
import {
  BugIcon,
  DeliverableIcon,
  EnablerIcon,
  EpicIcon,
  FlagIcon,
  MilestoneIcon,
  PriorityHighIcon,
  PriorityLowIcon,
  PriorityMediumIcon,
  ProjectIcon,
  SpikeIcon,
  StoryIcon,
  TaskIcon,
  WarningIcon,
} from "../components/icons";

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
