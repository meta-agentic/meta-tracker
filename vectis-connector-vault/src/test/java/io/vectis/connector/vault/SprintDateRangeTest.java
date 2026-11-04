// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A sprint date Java parses but PostgreSQL's date type rejects is a problem in the file, not a failed import. */
class SprintDateRangeTest {

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"0000-01-01", "+10000-01-01", "-0001-01-01", "+999999999-01-01"})
    void aYearOutsideOneToNineNineNineNineIsNotADate(String date) throws IOException {
        BacklogSnapshot snapshot = read("start: '" + date + "'\nend: '9999-12-31'\nclosed: '0001-01-01'\n");

        assertNull(snapshot.sprints().get(0).start());
        assertEquals(LocalDate.of(9999, 12, 31), snapshot.sprints().get(0).end());
        assertEquals(LocalDate.of(1, 1, 1), snapshot.sprints().get(0).closed());
        List<String> problems = Fixtures.problemsAbout(snapshot, "sprints/DEMO-S1.md");
        assertEquals(1, problems.size(), snapshot.problems()::toString);
        assertTrue(problems.get(0).endsWith("'start' is not a date (YYYY-MM-DD); left empty"), problems::toString);
    }

    private BacklogSnapshot read(String dates) throws IOException {
        Path space = Files.createDirectories(temp.resolve("vault/demo/sprints").getParent());
        Files.writeString(space.resolve("_backlog-meta.yaml"), "idPolicy:\n  prefix: DEMO\n");
        Files.createDirectories(space.resolve("sprints"));
        Files.writeString(space.resolve("sprints/DEMO-S1.md"),
                "---\nkind: sprint\nsprintId: DEMO-S1\nstate: active\n" + dates + "---\n");
        return new VaultBacklogSource(temp.resolve("vault"), "demo").read();
    }
}
