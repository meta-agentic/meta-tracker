---
kind: story
space: vec
id: VEC-13
title: Decoupled Extension Interface & Plugin Loading Architecture
status: DONE
project: VEC
epic: VEC-4
sprint: VEC-S1
storyPoints: 3.0
priority: P2
labels:
- Sprint-1
- VECTIS-026
- edition-community
- spike
- tier-Extensibility-Architecture
---

## Description

As a Platform Product Owner, I want us to evaluate runtime plugin discovery and modular compilation patterns for the Vectis engine, So that commercial modules can be developed, packaged, and licensed completely independently without modifying, contaminating, or recompiling the core Apache 2.0 code repository.

**Acceptance Criteria**

* Prototype a mechanism where the open-source core defines extensible interface stubs (e.g., AuditLogger, SyncConnector) that can programmatically discover external implementations at runtime.
* Demonstrate that if a commercial plugin JAR is omitted from the deployment image, the Vectis core boots up successfully and gracefully falls back to native community capabilities without dependency injection faults.
* Ensure the architectural boundary prevents commercial code from bleeding into open-source classloaders, maintaining clean licensing compliance.

**Developer Notes**
Investigate Quarkus's programmatic CDI bean resolution using jakarta.enterprise.inject.Instance combined with conditional annotations (@LookupIfProperty or @All). Ensure that the runtime classpath scans do not add blocking latency or overhead to the application's native-compiled bootstrap loop.

---

*Backlog ref: VECTIS-026 · Type: Spike · Tier: Extensibility Architecture · Planned: Architecture Spike 1*
