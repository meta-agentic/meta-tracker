// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.persistence.BoardRepository;
import io.vectis.persistence.ItemRepository;
import io.vectis.persistence.KeyedBoard;
import io.vectis.persistence.PlacedItem;
import io.vectis.persistence.WorkspaceRepository;
import io.vectis.persistence.WorkspaceSummary;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The workspace reads a board client hydrates from (VEC-94): the workspaces, a workspace's boards
 * with their columns, and its items a page at a time.
 */
@Path("/api/v1/workspaces")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspacesResource {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final WorkspaceRepository workspaces;
    private final BoardRepository boards;
    private final ItemRepository items;

    public WorkspacesResource(WorkspaceRepository workspaces, BoardRepository boards, ItemRepository items) {
        this.workspaces = workspaces;
        this.boards = boards;
        this.items = items;
    }

    /** Every workspace, newest first. */
    @GET
    public Uni<List<WorkspaceView>> list() {
        return workspaces.listSummariesNewestFirst().map(all -> all.stream().map(WorkspaceView::of).toList());
    }

    @GET
    @Path("/{key}")
    public Uni<WorkspaceView> get(@PathParam("key") String key) {
        return workspace(key).map(WorkspaceView::of);
    }

    /** The workspace's boards in the order they were created, each with its columns in ordinal order. */
    @GET
    @Path("/{key}/boards")
    public Uni<List<BoardView>> boards(@PathParam("key") String key) {
        return workspace(key)
                .chain(ws -> boards.findByWorkspace(ws.id()))
                .map(all -> all.stream().map(BoardView::of).toList());
    }

    /**
     * The workspace's items, newest first, {@code limit} at a time; {@code next} is the cursor of
     * the following page, null on the last one.
     *
     * @param board  narrows to the board with this key
     * @param column narrows to the column with this key, on {@code board} or on any board
     * @param cursor the {@code next} of the previous page
     */
    @GET
    @Path("/{key}/items")
    public Uni<ItemPage> items(@PathParam("key") String key, @QueryParam("board") String board,
                               @QueryParam("column") String column, @QueryParam("cursor") String cursor,
                               @QueryParam("limit") String limit) {
        int size = limit(limit);
        UUID before = cursor == null ? null : Cursor.decode(cursor);
        if (column != null && !BoardColumn.KEY.matcher(column).matches()) {
            throw ProblemException.badRequest("column is a column key; got '" + column + "'");
        }
        return workspace(key)
                .chain(ws -> boards.findByWorkspace(ws.id())
                        .map(all -> scope(key, all, board, column))
                        .chain(boardId -> items.pageNewestFirst(ws.id(), boardId, column, before, size + 1)))
                .map(page -> ItemPage.of(page, size));
    }

    private Uni<WorkspaceSummary> workspace(String key) {
        return workspaces.findSummary(key)
                .map(found -> found.orElseThrow(() -> ProblemException.notFound("workspace " + key + " does not exist")));
    }

    /** The id of the board the filter names, null for every board; a key that names nothing is a 404. */
    private static UUID scope(String workspace, List<KeyedBoard> all, String board, String column) {
        List<Board> named = all.stream().filter(b -> board == null || b.key().equals(board)).map(KeyedBoard::board)
                .toList();
        if (named.isEmpty()) {
            throw ProblemException.notFound("workspace " + workspace + " has no board " + board);
        }
        if (column != null && named.stream().flatMap(b -> b.columns().stream()).noneMatch(c -> c.key().equals(column))) {
            throw ProblemException.notFound("workspace " + workspace + " has no column " + column
                    + (board == null ? "" : " on board " + board));
        }
        return board == null ? null : named.get(0).id();
    }

    private static int limit(String limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        int size;
        try {
            size = Integer.parseInt(limit);
        } catch (NumberFormatException e) {
            size = 0;
        }
        if (size < 1 || size > MAX_LIMIT) {
            throw ProblemException.badRequest("limit is a whole number from 1 to " + MAX_LIMIT + "; got '" + limit + "'");
        }
        return size;
    }

    record WorkspaceView(String key, String name, TemplateRefView template) {
        static WorkspaceView of(WorkspaceSummary ws) {
            return new WorkspaceView(ws.key(), ws.name(),
                    new TemplateRefView(ws.template().key(), ws.template().version()));
        }
    }

    record TemplateRefView(String key, int version) {
    }

    record ColumnView(String key, String name, int ordinal, boolean onBoard) {
        static ColumnView of(BoardColumn column) {
            return new ColumnView(column.key(), column.name(), column.position(), column.onBoard());
        }
    }

    record BoardView(String key, String name, List<ColumnView> columns) {
        static BoardView of(KeyedBoard keyed) {
            return new BoardView(keyed.key(), keyed.board().name(),
                    keyed.board().orderedColumns().stream().map(ColumnView::of).toList());
        }
    }

    /** {@code version} is the revision a later write takes back as {@code If-Match}. */
    record ItemView(String key, String title, String board, String column, String rank, Map<String, Object> fields,
                    long version) {
    }

    record ItemPage(List<ItemView> items, String next) {
        /** {@code page} holds up to one item more than {@code size}: its presence says there is a next page. */
        static ItemPage of(List<PlacedItem> page, int size) {
            List<PlacedItem> shown = page.size() > size ? page.subList(0, size) : page;
            String next = page.size() > size ? Cursor.encode(shown.get(size - 1).item().id()) : null;
            return new ItemPage(shown.stream().map(p -> new ItemView(p.item().key(), p.item().title(),
                    p.boardKey(), p.columnKey(), p.item().rank(), p.item().fields(), p.item().version())).toList(), next);
        }
    }

    /** The opaque position a page ends at: the last item's id, unreadable as one. */
    static final class Cursor {

        private Cursor() {
        }

        static String encode(UUID id) {
            ByteBuffer bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
        }

        static UUID decode(String cursor) {
            byte[] bytes;
            try {
                bytes = Base64.getUrlDecoder().decode(cursor);
            } catch (IllegalArgumentException e) {
                bytes = new byte[0];
            }
            if (bytes.length != 16) {
                throw ProblemException.badRequest("cursor is not one this API issued: '" + cursor + "'");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    }
}
