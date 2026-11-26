import fixtureJson from "../../../vectis-command/src/test/resources/conformance/item-rules.json";
import { check, mergePatch, type Effective, type Write } from "./rules";

/**
 * The client's side of the conformance fixture the command layer also runs
 * (vectis-command FieldRulesConformanceTest): the same writes against the
 * shipped built-ins must give the same violations, as [field, code], in order.
 */
const goldens = import.meta.glob<Record<string, unknown>>(
  "../../../vectis-domain/src/test/resources/templates/golden/*.v1.resolved.json",
  { eager: true, import: "default" },
);

interface Case {
  name: string;
  template: string;
  delta?: Record<string, unknown>;
  write: Record<string, unknown>;
  expect: [string, string][];
}

const fixture = fixtureJson as unknown as { cases: Case[] };

function effective(template: string, delta?: Record<string, unknown>): Effective {
  const golden = goldens[`../../../vectis-domain/src/test/resources/templates/golden/${template}.v1.resolved.json`];
  if (!golden) throw new Error(`no golden resolution for ${template}`);
  return (delta ? mergePatch(golden, delta) : golden) as unknown as Effective;
}

function write(raw: Record<string, unknown>): Write {
  const item = raw.item as { state: string; fields: Record<string, unknown> } | undefined;
  return { ...raw, item: item && { state: item.state, fields: item.fields ?? {} } } as unknown as Write;
}

describe("the item rules agree with the command layer's", () => {
  it("has cases to agree on", () => {
    expect(fixture.cases.length).toBeGreaterThan(30);
  });

  it.each(fixture.cases.map((c) => [c.name, c] as const))("%s", (_, c) => {
    const got = check(effective(c.template, c.delta), write(c.write)).map((v) => [v.field, v.code]);
    expect(got).toEqual(c.expect);
  });
});
