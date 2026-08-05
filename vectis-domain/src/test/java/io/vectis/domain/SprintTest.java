package io.vectis.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SprintTest {

    @Test
    void createMintsAFutureSprintWithNoTimestamps() {
        Sprint sprint = Sprint.create(UUID.randomUUID(), "Sprint 1");

        assertEquals(SprintStatus.FUTURE, sprint.status());
        assertNull(sprint.startedAt());
        assertNull(sprint.completedAt());
    }

    @Test
    void startMovesFutureToActiveAndStampsStartedAt() {
        Sprint started = Sprint.create(UUID.randomUUID(), "Sprint 1").start();

        assertEquals(SprintStatus.ACTIVE, started.status());
        assertNotNull(started.startedAt());
        assertNull(started.completedAt());
    }

    @Test
    void completeMovesActiveToCompletedAndStampsCompletedAt() {
        Sprint completed = Sprint.create(UUID.randomUUID(), "Sprint 1").start().complete();

        assertEquals(SprintStatus.COMPLETED, completed.status());
        assertNotNull(completed.startedAt());
        assertNotNull(completed.completedAt());
    }

    @Test
    void startIsIllegalUnlessFuture() {
        Sprint active = Sprint.create(UUID.randomUUID(), "Sprint 1").start();

        assertThrows(IllegalStateException.class, active::start);
        assertThrows(IllegalStateException.class, active.complete()::start);
    }

    @Test
    void completeIsIllegalUnlessActive() {
        Sprint future = Sprint.create(UUID.randomUUID(), "Sprint 1");

        assertThrows(IllegalStateException.class, future::complete);
        assertThrows(IllegalStateException.class, future.start().complete()::complete);
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Sprint.create(UUID.randomUUID(), "  "));
    }

    @Test
    void nonFutureStatusRequiresStartedAt() {
        UUID id = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> new Sprint(id, boardId, "Sprint 1", SprintStatus.ACTIVE, null, null));
    }

    @Test
    void completedStatusRequiresCompletedAt() {
        UUID id = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        var startedAt = java.time.Instant.now();

        assertThrows(IllegalArgumentException.class,
                () -> new Sprint(id, boardId, "Sprint 1", SprintStatus.COMPLETED, startedAt, null));
    }
}
