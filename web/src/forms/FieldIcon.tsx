import { FIELD_ICONS } from "./fieldIcons";

/** The named icon, decorative: the control that shows it carries its name. Nothing for a name it does not know. */
export function FieldIcon({ name, size = 16 }: { name: string | undefined; size?: number }) {
  const Glyph = name ? FIELD_ICONS[name] : undefined;
  return Glyph ? <Glyph size={size} /> : null;
}
