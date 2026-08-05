package io.vectis.domain;

/**
 * The lifecycle of a {@link Sprint}: future, then active, then completed. No state is
 * skipped or revisited — see {@link Sprint#start()} and {@link Sprint#complete()}.
 */
public enum SprintStatus {
    FUTURE,
    ACTIVE,
    COMPLETED
}
