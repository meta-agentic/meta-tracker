# syntax=docker/dockerfile:1

# Multi-stage distroless image for the Vectis native (GraalVM) server.
#
# The native executable is produced ahead of this build by the CI native
# compile (`mvn verify -Dnative`, see .github/workflows/native-deploy.yml) or a
# local `./mvnw package -Dnative`, and lands at vectis-server/target/*-runner.
#
# Stage `prep` normalises that binary — a stable name, an exec bit, and non-root
# ownership — using a minimal UBI base with a shell. The final stage is Quarkus'
# distroless runtime: it carries only glibc and zlib (which the native binary
# links against) plus a pre-created non-root user (uid 1001), with no shell and
# no package manager, keeping the attack and CVE surface minimal.

# Base images are digest-pinned (tag kept for readability) so the runtime is
# reproducible and immune to a repushed tag — matching the SHA-pinned Actions
# and the repo's OpenSSF Scorecard pinned-dependencies posture.
ARG DISTROLESS_IMAGE=quay.io/quarkus/quarkus-distroless-image:2.0@sha256:11a595130bf1ef65542ca52e70ca3af06e904a08c73675d124831f131b455d12
ARG PREP_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:9.6@sha256:34880b64c07f28f64d95737f82f891516de9a3b43583f39970f7bf8e4cfa48b7

FROM ${PREP_IMAGE} AS prep
WORKDIR /stage
# Fails the build loudly if the native runner is missing rather than shipping an
# empty image (the glob would otherwise silently match nothing).
COPY vectis-server/target/*-runner ./application
RUN chmod 0555 ./application

FROM ${DISTROLESS_IMAGE}
WORKDIR /work
COPY --from=prep --chown=1001:root /stage/application /work/application

EXPOSE 8080
USER 1001

# Bind to all interfaces so the container is reachable; the readiness/liveness
# probes and Argo CD health checks hit /q/health on this port.
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
