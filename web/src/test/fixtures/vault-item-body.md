
## Why

Found while building VEC-10's server slice (branch `vec/VEC-10-template-provisioning`, not yet
ratified — see VEC-10's note). Once `vectis-server` depends on `vectis-persistence` at all, it
needs PostgreSQL at startup via `VECTIS_DB_*` environment variables, and `VECTIS_DB_PASSWORD` has
no default. `deploy/k8s` supplies none of these today, so the deployed image will not boot. CI's
jvm-test and native-deploy jobs already provision a database for tests, which is why this gap was
invisible until now.

This item tracks the deploy gap itself, independent of whether VEC-10's specific branch is the
one that first needs it — SOME story will add a persistence-backed server resource, and this must
be fixed before that server ships anywhere.

## Acceptance criteria

- [ ] `deploy/k8s` provisions or references a PostgreSQL instance/secret for `vectis-server`,
      with `VECTIS_DB_PASSWORD` sourced from a Secret, never a plaintext default.
- [ ] A fresh deploy of any `vectis-server` build that has a persistence dependency boots
      successfully, verified on the target cluster.
