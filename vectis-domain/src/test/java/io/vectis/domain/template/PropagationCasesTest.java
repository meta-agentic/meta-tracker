package io.vectis.domain.template;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The propagation cases of the VEC-66 spike ({@code PropagationCases.java}), plus H8 from
 * {@code HierarchyCases.java}, which exercises the release build's rebase. Propagation (the
 * release build and the runtime job) is VEC-71's scope, not the resolver's, so each case is
 * carried here, disabled, until VEC-71 ports {@code Propagation.java} and enables it.
 */
@Disabled("propagation is VEC-71: port docs/spikes/template-instance-model/Propagation.java and enable these cases")
class PropagationCasesTest {

    @Test
    @DisplayName("P1 a change at the root reaches a workspace four levels down, through the release build and then automatic edges; no delta on the way is rewritten")
    void p1() {
    }

    @Test
    @DisplayName("P2 one configuration.changed per re-pinned workspace, each naming the version originally published; none for a workspace left behind")
    void p2() {
    }

    @Test
    @DisplayName("P3 nearest level wins, field by field: Scrum renames In Review; Acme had renamed it, so Acme's teams keep Code Review; plain Scrum workspaces follow")
    void p3() {
    }

    @Test
    @DisplayName("P4 a manual edge stops propagation: Acme stays on scrum@1 and is shown the dry run; nothing below it moves; it upgrades later, explicitly")
    void p4() {
    }

    @Test
    @DisplayName("P5 occupancy at the next level: a busy workspace is refused and stays, an idle one deletes the column; a customised state is promoted into acme@2")
    void p5() {
    }

    @Test
    @DisplayName("P6 occupancy four levels down: every template level rebases, the busy workspace is refused, its idle sibling deletes the column")
    void p6() {
    }

    @Test
    @DisplayName("P7 a conflict at a template edge: Ops is refused and stays with its workspaces, Scrum's other children move on")
    void p7() {
    }

    @Test
    @DisplayName("P8 a parent that adds a lock on a path a child overrides refuses the child's upgrade, at a template edge and at a workspace")
    void p8() {
    }

    @Test
    @DisplayName("P9 a manual workspace sees each publish as a preview and may skip versions; a skip can differ from the stepwise path")
    void p9() {
    }

    @Test
    @DisplayName("P10 apply recomputes in the workspace's own transaction from its current pin and delta; a workspace already at or past the target is skipped")
    void p10() {
    }

    @Test
    @DisplayName("P11 two root versions propagated out of order: only an older pin is upgraded, so nothing is moved back")
    void p11() {
    }

    @Test
    @DisplayName("P12 two publishes planned from scrum@1 and applied in order: the second is recomputed from scrum@2; replaying a row is a no-op")
    void p12() {
    }

    @Test
    @DisplayName("H8 built-in edges rebase at build time: a release of base@2 adds one reviewed file per rebased built-in; runtime propagation never publishes a built-in")
    void h8() {
    }
}
