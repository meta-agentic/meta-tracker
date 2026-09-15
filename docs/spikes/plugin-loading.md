# Decoupled Extension & Plugin Loading (Spike Findings)

**Status:** prototype / spike. Working code lands in this PR; it is deliberately
minimal and is **not** production-hardened. See
[Prototype vs. hardening](#prototype-quality-vs-what-needs-hardening).

## Goal

Prove a mechanism by which the Apache-2.0 community core defines extensible
interface stubs, and commercial (or third-party) modules can be developed,
packaged, and **licensed independently** and discovered **at runtime** — without
modifying or recompiling the OSS core, and without commercial code entering the
OSS repository.

## Acceptance criteria — how each is met

| AC | Where it is demonstrated |
|----|--------------------------|
| **1. OSS core defines extensible interface stubs discovered at runtime** | `vectis-extension-spi` defines framework-free `AuditLogger` / `SyncConnector`. `vectis-core`'s `ExtensionRegistry` resolves implementations programmatically via ArC (`@All List<T>`, `Instance<T>`). `ExtensionRegistryOverrideTest` proves a higher-priority plugin, gated on at runtime, is selected over the community floor with no core code change. |
| **2. Omit the commercial JAR → core boots + falls back, no DI fault** | Default reactor build has no commercial plugin on the server classpath. `ServerBootFallbackTest` boots the whole Quarkus app and asserts the active audit logger is `community-local` and sync is off. Also verified at runtime against the packaged fast-jar. |
| **3. Boundary keeps commercial code out of the OSS core / licensing compliance** | The example plugin (`vectis-plugin-audit-enterprise`) compiles against **only** `vectis-extension-spi` (+ ArC) — it has no dependency on `vectis-core` or `vectis-server`. The OSS server never depends on the plugin. See [Licensing boundary](#licensing--classloader-boundary) for exactly how strong this is. |

## Module layout (spike)

```
vectis-extension-spi              Pure-Java contract. The ONLY artifact a plugin
                                  compiles against. No framework, no engine code.
vectis-core                       Discovery engine (ExtensionRegistry) + the
                                  always-present community fallback beans.
vectis-plugin-audit-enterprise    Example commercial plugin. In the real open-core
                                  split this lives OUT of this repo; included here
                                  only to demonstrate end-to-end discovery.
vectis-server                     Runnable composition root. Depends on core, NOT
                                  on any plugin.
```

This is intentionally a thin slice, not the full `domain / api / persistence /
rest / server` hexagon from the README — only what the plugin-loading question
needs.

## Discovery mechanism

- Extension points are ordinary CDI bean types (`AuditLogger`, `SyncConnector`).
- The core resolves them **programmatically** with ArC:
  - `@All List<T>` — every discovered implementation, **sorted by `@Priority`
    (highest first)**. Selection is simply "take element 0", so a plugin
    declaring a higher priority supersedes the community default with zero core
    changes.
  - `Instance<T>` — the programmatic lookup handle; used for a resolvable-bean
    count in diagnostics.
- A plugin JAR is discovered **purely by being on the deployment classpath**: it
  ships a `META-INF/beans.xml` so ArC indexes it as a bean archive. The core has
  no registration list, no service-loader file, no config entry to edit.
- Resolution is computed **once, at build-time augmentation** by ArC — there is
  **no runtime classpath scan**, so discovery adds nothing to the native /
  boot loop. This directly satisfies the dev-note performance concern.

### Licensing / feature gating

The example plugin is gated with
`@LookupIfProperty(name = "vectis.enterprise.audit.enabled", stringValue = "true")`.
The bean is registered but only *looked up* when the flag is true — a
deploy-time license switch. Off (or absent) → the community floor is used.

## Key finding: don't build the community floor with `@DefaultBean`

The first cut made the community implementations `@DefaultBean` (only registered
when no other bean of the type exists). It looked elegant, but it **fails closed
in the wrong direction** when combined with a runtime-gated commercial bean:

- `@DefaultBean` suppression is decided at **build time**, by the mere
  *presence* of another bean of the type.
- `@LookupIfProperty` gates that bean at **runtime**.
- So when a commercial plugin JAR is present but its license flag is **off**:
  the community `@DefaultBean` was already suppressed at build time, *and* the
  commercial bean is excluded at runtime → the discovery lookup is **empty** →
  `IndexOutOfBoundsException` on boot/first request. Reproduced during the spike
  (HTTP 500, empty `@All` list).

**Resolution:** the community floor is a plain, **always-present**
`@Priority(0)` bean (not `@DefaultBean`). A licensed commercial plugin
(`@Priority(100)`) simply outranks it; an unlicensed-but-present plugin cleanly
falls through to it. The floor is never removed, so the lookup is never empty.
Verified at runtime:

| Deployment | `activeAuditLogger` | discovered |
|-----------|---------------------|-----------|
| Plugin omitted (default OSS build) | `community-local` | `[community-local]` |
| Plugin present, **licensed** | `enterprise-tamper-evident` | `[enterprise-tamper-evident, community-local]` |
| Plugin present, **unlicensed** | `community-local` | `[community-local]` |

## Licensing / classloader boundary

AC #3 asks the boundary to "prevent commercial code from bleeding into OSS
classloaders." Being precise about what the prototype actually enforces:

- **Enforced today (compile + packaging boundary):**
  - The plugin depends on **only** `vectis-extension-spi`. It cannot reference
    `vectis-core` / `vectis-server` internals because they are not on its
    compile classpath.
  - The OSS reactor never depends on the commercial artifact; the community
    image is built without it. "Licensing compliance" is therefore a build-graph
    property, checkable in CI (assert no OSS module depends on a commercial
    coordinate).
- **NOT enforced today (runtime isolation):** in Quarkus JVM/fast-jar mode all
  application and dependency JARs share one flat runtime classloader. Once a
  commercial JAR *is* in the image, it is not sandboxed in a separate
  classloader. True per-plugin classloader isolation (e.g. a module layer or a
  child classloader per plugin, exposing only the SPI) is a **hardening item**,
  not part of this spike.

The open-core boundary this project needs is the compile/packaging one, and that
is clean. Runtime classloader isolation matters only if we later run *untrusted*
third-party plugins in the same process.

## How to run / demonstrate

```bash
# Default build — plugin omitted. Proves graceful community fallback.
mvn clean verify

# Package the server WITH the example plugin bundled, then toggle the license:
mvn -pl vectis-server -am -Pwith-enterprise-audit -DskipTests package
java -Dvectis.enterprise.audit.enabled=true  -jar vectis-server/target/quarkus-app/quarkus-run.jar
java -Dvectis.enterprise.audit.enabled=false -jar vectis-server/target/quarkus-app/quarkus-run.jar
# then: curl localhost:8080/api/extensions
```

## Prototype-quality vs. what needs hardening

**Prototype-quality (as shipped):**
- `TamperEvidentAuditLogger`'s hash chain is in-memory only — not persisted,
  signed, or externally shipped. It exists to show a plugin *doing something*.
- `SyncConnector` is a stub (id + availability) with no actual push/pull.
- Diagnostics endpoint is unauthenticated and returns a plain snapshot.
- The example plugin lives in this repo for demonstration; in the real split it
  is a separate, separately-licensed repository/artifact.

**Would need hardening before real commercial-plugin use:**
- A CI guard asserting no OSS module depends on a commercial coordinate.
- A published, versioned SPI with an explicit compatibility policy (plugins
  compiled against SPI vX must keep working).
- Decide whether third-party plugins run in-process (then add per-plugin
  classloader isolation + a permissions model) or out-of-process.
- Health/observability: surface active vs. inactive (unlicensed) plugins, and
  fail loudly if a *required* licensed plugin is configured but its flag is off.
- Native-image verification: confirm the `@LookupIfProperty` gate and bean
  discovery behave identically under `-Pnative` (expected to, since resolution
  is build-time, but unverified in this spike).

## Recommendation

Adopt the pattern: **framework-free SPI artifact + always-present `@Priority(0)`
community floor + programmatic ArC discovery (`@All`) + `@LookupIfProperty`
license gating + separate out-of-tree commercial artifacts**. Avoid
`@DefaultBean` for any extension point that a runtime-gated implementation can
target. Enforce the open-core boundary in CI at the dependency-graph level.
