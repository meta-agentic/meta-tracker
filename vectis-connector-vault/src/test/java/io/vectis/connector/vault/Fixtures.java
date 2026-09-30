// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.SourceItem;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Fixture vault trees under {@code src/test/resources/vaults/<name>/vault}, all in space {@code demo}. */
final class Fixtures {

    static final String SPACE = "demo";

    private Fixtures() {
    }

    static Path vault(String name) {
        try {
            return Path.of(Objects.requireNonNull(
                    Fixtures.class.getResource("/vaults/" + name + "/vault"), name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static BacklogSnapshot read(String name) {
        return new VaultBacklogSource(vault(name), SPACE).read();
    }

    static SourceItem item(BacklogSnapshot snapshot, String key) {
        return snapshot.items().stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " not in snapshot: " + keys(snapshot)));
    }

    static List<String> keys(BacklogSnapshot snapshot) {
        return snapshot.items().stream().map(SourceItem::key).toList();
    }

    /** The problems that name a given file, e.g. {@code "raw/DEMO-5.md"}. */
    static List<String> problemsAbout(BacklogSnapshot snapshot, String path) {
        return snapshot.problems().stream().filter(problem -> problem.contains("/" + path + ":")).toList();
    }
}
