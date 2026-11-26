import type { FieldSpec } from "./definition";
import { FIELD_ICONS, KIND_ICONS, kindOf } from "./fieldIcons";

/** The named icon, decorative: the control that shows it carries its name. Nothing for a name it does not know. */
export function FieldIcon({ name, size = 16 }: { name: string | undefined; size?: number }) {
  const Glyph = name ? FIELD_ICONS[name] : undefined;
  return Glyph ? <Glyph size={size} /> : null;
}

/**
 * The glyph of a field's kind, drawn before its label. Decorative like every
 * icon: the label names the field, and the input itself says what it takes.
 */
export function FieldKindIcon({ field }: { field: Pick<FieldSpec, "kind" | "numeric"> }) {
  const kind = kindOf(field);
  const Glyph = KIND_ICONS[kind];
  return <Glyph className="vec-form__kind" data-kind={kind} />;
}
