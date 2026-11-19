// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.template.Violation;
import io.vectis.persistence.BoardRepository;
import io.vectis.persistence.ItemNotFoundException;
import io.vectis.persistence.ItemRepository;
import io.vectis.persistence.ItemVersionConflictException;
import io.vectis.persistence.KeyedBoard;
import io.vectis.persistence.NewItem;
import io.vectis.persistence.PlacedItem;
import io.vectis.persistence.PlacementException;
import io.vectis.persistence.SourceBoundWorkspaceException;
import io.vectis.persistence.WorkspaceRepository;
import io.vectis.persistence.WorkspaceSummary;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Creates, edits, moves and deletes items: the one way any channel changes an item (ADR-VEC-04 D8).
 * Every command is scoped to the workspace it names, and an item is found by its key in that
 * workspace only, so a command cannot reach another workspace's item.
 *
 * <p>A command is checked in this order, and the first refusal is the answer: the workspace exists
 * ({@link UnknownKeyException}); it is not bound to a backlog source ({@link ReadOnlyWorkspaceException});
 * a change to an existing item names its version ({@link VersionRequiredException}); the item
 * exists ({@link UnknownKeyException}); it is still at that version ({@link StaleVersionException});
 * the command keeps the rules ({@link CommandRejectedException}, with every violation). The write
 * itself checks the binding and the version again under the workspace lock, so a change that lands
 * between the check and the write is refused the same way.
 *
 * <p>Each write raises the item's version and appends one workspace event in its own transaction,
 * tagged with the command's {@code origin}. An edit that changes nothing writes nothing.
 */
@ApplicationScoped
public class ItemCommands {

    private final WorkspaceRepository workspaces;
    private final BoardRepository boards;
    private final ItemRepository items;

    public ItemCommands(WorkspaceRepository workspaces, BoardRepository boards, ItemRepository items) {
        this.workspaces = workspaces;
        this.boards = boards;
        this.items = items;
    }

    /** Creates the item and answers it as written, at version 1. */
    public Uni<PlacedItem> create(CreateItem command) {
        return writable(command.workspace())
                .chain(ws -> boards.findByWorkspace(ws.id()).chain(all -> {
                    List<Violation> violations = new ArrayList<>();
                    ItemRules.title(command.title(), violations);
                    ItemRules.rank(command.rank(), violations);
                    ItemRules.fieldNames(command.fields(), violations);
                    Optional<KeyedBoard> board = board(ws.key(), all, command.board(), violations);
                    Optional<BoardColumn> column = board.flatMap(b -> column(b, command.column(), violations));
                    if (!violations.isEmpty()) {
                        throw new CommandRejectedException(violations);
                    }
                    NewItem draft = new NewItem(ws.id(), board.orElseThrow().board().id(), column.orElseThrow().id(),
                            command.title(), command.rank(), MergePatch.apply(Map.of(), command.fields()));
                    return items.create(draft, command.origin())
                            .map(saved -> new PlacedItem(saved, board.get().key(), column.get().key(), null));
                }))
                .onFailure().transform(refusal(command.workspace(), null, null));
    }

    /** Edits the item's title and fields and answers it as written. */
    public Uni<PlacedItem> edit(EditItem command) {
        return current(command.workspace(), command.item(), command.expectedVersion())
                .chain(target -> {
                    List<Violation> violations = new ArrayList<>();
                    if (command.title() != null) {
                        ItemRules.title(command.title(), violations);
                    }
                    ItemRules.fieldNames(command.fields(), violations);
                    if (!violations.isEmpty()) {
                        throw new CommandRejectedException(violations);
                    }
                    Item item = target.placed().item();
                    Item edited = item.withTitle(command.title() == null ? item.title() : command.title())
                            .withFields(MergePatch.apply(item.fields(), command.fields()));
                    return items.edit(edited, command.expectedVersion(), command.origin())
                            .map(saved -> target.placedAs(saved, target.placed().columnKey()));
                })
                .onFailure().transform(refusal(command.workspace(), command.item(), command.expectedVersion()));
    }

    /** Moves the item to a column of its board, at a rank, and answers it as written. */
    public Uni<PlacedItem> move(MoveItem command) {
        return current(command.workspace(), command.item(), command.expectedVersion())
                .chain(target -> boards.findByWorkspace(target.workspace().id()).chain(all -> {
                    Item item = target.placed().item();
                    List<Violation> violations = new ArrayList<>();
                    ItemRules.rank(command.rank(), violations);
                    KeyedBoard board = all.stream().filter(b -> b.board().id().equals(item.boardId())).findFirst()
                            .orElseThrow();
                    Optional<BoardColumn> column = Optional.empty();
                    if (command.column() == null) {
                        violations.add(ItemRules.violation("column", "a move names the column the item moves to"));
                    } else {
                        column = column(board, command.column(), violations);
                    }
                    if (!violations.isEmpty()) {
                        throw new CommandRejectedException(violations);
                    }
                    String rank = command.rank() == null ? item.rank() : command.rank();
                    BoardColumn to = column.orElseThrow();
                    return items.move(item, to.id(), rank, command.expectedVersion(), command.origin())
                            .map(saved -> target.placedAs(saved, to.key()));
                }))
                .onFailure().transform(refusal(command.workspace(), command.item(), command.expectedVersion()));
    }

    /** Deletes the item. */
    public Uni<Void> delete(DeleteItem command) {
        return current(command.workspace(), command.item(), command.expectedVersion())
                .chain(target -> items.delete(target.placed().item(), command.expectedVersion(), command.origin()))
                .onFailure().transform(refusal(command.workspace(), command.item(), command.expectedVersion()));
    }

    /** The workspace, if it exists and a client may write to it. */
    private Uni<WorkspaceSummary> writable(String workspace) {
        return workspaces.findSummary(workspace).map(found -> {
            WorkspaceSummary ws = found.orElseThrow(() -> UnknownKeyException.workspace(workspace));
            if (ws.readOnly()) {
                throw new ReadOnlyWorkspaceException(workspace, ws.source());
            }
            return ws;
        });
    }

    /** The item a change targets, in its workspace, if it is still at {@code expected}. */
    private Uni<Target> current(String workspace, String item, Long expected) {
        return writable(workspace).chain(ws -> {
            if (expected == null) {
                throw new VersionRequiredException(item);
            }
            return items.findPlaced(ws.id(), item).map(found -> {
                PlacedItem placed = found.orElseThrow(() -> UnknownKeyException.item(workspace, item));
                if (placed.item().version() != expected) {
                    throw StaleVersionException.changed(item, expected, placed.item().version());
                }
                return new Target(ws, placed);
            });
        });
    }

    /** The named board, or the workspace's first; a key that names none is a violation. */
    private static Optional<KeyedBoard> board(String workspace, List<KeyedBoard> all, String key,
                                              List<Violation> violations) {
        Optional<KeyedBoard> board = key == null
                ? all.stream().findFirst()
                : all.stream().filter(b -> b.key().equals(key)).findFirst();
        if (board.isEmpty()) {
            violations.add(ItemRules.violation("board", key == null
                    ? "workspace " + workspace + " has no board"
                    : "workspace " + workspace + " has no board " + key));
        }
        return board;
    }

    /** The named column of the board, or its first; a key that names none is a violation. */
    private static Optional<BoardColumn> column(KeyedBoard board, String key, List<Violation> violations) {
        List<BoardColumn> columns = board.board().orderedColumns();
        Optional<BoardColumn> column = key == null
                ? columns.stream().findFirst()
                : columns.stream().filter(c -> c.key().equals(key)).findFirst();
        if (column.isEmpty()) {
            violations.add(ItemRules.violation("column", key == null
                    ? "board " + board.key() + " has no column"
                    : "board " + board.key() + " has no column " + key));
        }
        return column;
    }

    /**
     * What the persistence layer's refusals mean to the command's caller, named by keys: the write
     * checks the binding and the version again under the lock, and an item that was found before
     * the write but is gone at it was deleted after the version the command names.
     */
    private static Function<Throwable, Throwable> refusal(String workspace, String item, Long expected) {
        return failure -> switch (failure) {
            case CommandException known -> known;
            case SourceBoundWorkspaceException bound -> new ReadOnlyWorkspaceException(workspace, bound.source());
            case ItemVersionConflictException stale -> StaleVersionException.changed(item, stale.expected(),
                    stale.actual());
            case ItemNotFoundException _ when expected != null -> StaleVersionException.deleted(item, expected);
            case PlacementException _ -> new CommandRejectedException(List.of(ItemRules.violation("column",
                    "the column is not on the item's board")));
            default -> failure;
        };
    }

    private record Target(WorkspaceSummary workspace, PlacedItem placed) {

        PlacedItem placedAs(Item saved, String columnKey) {
            return new PlacedItem(saved, placed.boardKey(), columnKey, placed.sprintKey());
        }
    }
}
