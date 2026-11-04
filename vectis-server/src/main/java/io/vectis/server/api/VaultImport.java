// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vectis.connector.vault.VaultBacklogSource;
import io.vectis.domain.Workspace;
import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.BacklogSourceException;
import io.vectis.extension.spi.SourceSprint;
import io.vectis.persistence.BoardRepository;
import io.vectis.persistence.ImportResult;
import io.vectis.persistence.ItemRepository;
import io.vectis.persistence.KeyedBoard;
import io.vectis.persistence.PlacementException;
import io.vectis.persistence.SprintRepository;
import io.vectis.persistence.TemplateRef;
import io.vectis.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The vault import (VEC-54): reads the configured vault through the connector, provisions the
 * workspace named by the vault's key prefix if it does not exist, and upserts the sprints and the items into
 * its board. The vault is only read.
 */
@ApplicationScoped
public class VaultImport {

    /** The workspace is provisioned from the plain Scrum template. */
    static final TemplateRef TEMPLATE = TemplateRef.parse("scrum@1");

    /** The writer tag the import's events carry. */
    static final String ORIGIN = "vault-import";

    /** More problems than this are counted but not listed. */
    static final int MAX_PROBLEMS_LISTED = 50;

    private final VaultImportConfig config;
    private final WorkspaceRepository workspaces;
    private final BoardRepository boards;
    private final ItemRepository items;
    private final SprintRepository sprints;

    public VaultImport(VaultImportConfig config, WorkspaceRepository workspaces, BoardRepository boards,
                       ItemRepository items, SprintRepository sprints) {
        this.sprints = sprints;
        this.config = config;
        this.workspaces = workspaces;
        this.boards = boards;
        this.items = items;
    }

    /** Whether the vault import is configured, which is whether its endpoint exists. */
    boolean configured() {
        return config.root().filter(root -> !root.isBlank()).isPresent();
    }

    Uni<Report> run() {
        var source = new VaultBacklogSource(Path.of(config.root().orElseThrow()), config.space());
        return Uni.createFrom().item(source::read)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure(BacklogSourceException.class).transform(e -> ProblemException.unprocessable(
                        e.getMessage(), List.of()))
                .chain(snapshot -> {
                    if (!snapshot.complete()) {
                        throw ProblemException.unprocessable("the vault was not read to the end, so nothing was"
                                + " imported: " + snapshot.problems().stream()
                                .filter(p -> p.startsWith(BacklogSnapshot.FATAL)).findFirst().orElse(""), List.of());
                    }
                    return write(snapshot);
                });
    }

    private Uni<Report> write(BacklogSnapshot snapshot) {
        String key = snapshot.keyPrefix();
        var plan = ImportedSprints.of(snapshot.sprints());
        var known = snapshot.sprints().stream().map(SourceSprint::key).collect(Collectors.toSet());
        var problems = new ArrayList<>(snapshot.problems());
        problems.addAll(plan.problems());
        var imported = snapshot.items().stream().map(item -> {
            boolean placed = item.sprintKey() != null && known.contains(item.sprintKey());
            if (item.sprintKey() != null && !placed) {
                problems.add(item.origin() + ": sprint '" + item.sprintKey()
                        + "' is not in the vault; the item is left without a sprint");
            }
            return ImportedItems.of(item, placed ? item.sprintKey() : null);
        }).toList();
        return workspaces.findByKey(key)
                .chain(found -> found.map(ws -> Uni.createFrom().item(new Provisioned(ws, false)))
                        .orElseGet(() -> provision(key)))
                .chain(target -> boards.findByWorkspace(target.workspace().id())
                        .chain(all -> {
                            if (all.isEmpty()) {
                                throw ProblemException.conflict("workspace " + key + " has no board to import into");
                            }
                            KeyedBoard board = all.getFirst();
                            return sprints.upsertImported(board.board().id(), plan.sprints(), ORIGIN)
                                    .chain(sprintResult -> items.upsertAll(board.board().id(), imported, ORIGIN));
                        })
                        .onFailure(PlacementException.class).transform(e -> ProblemException.unprocessable(
                                "workspace " + key + " cannot hold the vault's items: " + e.getMessage(), List.of()))
                        .map(result -> report(key, target.created(), result, problems)));
    }

    /**
     * Provisions the workspace. Two imports that both found none race on the workspace key: the
     * loser finds the winner's workspace, which is the one it wanted.
     */
    private Uni<Provisioned> provision(String key) {
        return workspaces.provision(Workspace.create(key, config.workspaceName()), null, TEMPLATE, null)
                .map(p -> new Provisioned(p.workspace(), true))
                .onFailure().recoverWithUni(failure -> workspaces.findByKey(key)
                        .map(found -> new Provisioned(found.orElseThrow(() -> new RuntimeException(failure)), false)));
    }

    private static Report report(String key, boolean provisioned, ImportResult result, List<String> problems) {
        return new Report(key, provisioned, result.created(), result.updated(), result.unchanged(), problems.size(),
                problems.stream().limit(MAX_PROBLEMS_LISTED).toList());
    }

    private record Provisioned(Workspace workspace, boolean created) {
    }

    /** What an import did. {@code problems} lists what the connector skipped or degraded, up to a limit. */
    public record Report(String workspace, boolean provisioned, int created, int updated, int unchanged,
                         int problemCount, List<String> problems) {
    }
}
