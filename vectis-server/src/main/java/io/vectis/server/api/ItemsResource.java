// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import io.vectis.command.CreateItem;
import io.vectis.command.DeleteItem;
import io.vectis.command.EditItem;
import io.vectis.command.ItemCommands;
import io.vectis.command.MoveItem;
import io.vectis.persistence.ItemRepository;
import io.vectis.persistence.PlacedItem;
import io.vectis.persistence.WorkspaceRepository;
import io.vectis.server.api.WorkspacesResource.ItemView;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Set;

/**
 * One item of a workspace (VEC-16): read it, and create, edit, move and delete it. Every write goes
 * through the command layer ({@link ItemCommands}, ADR-VEC-04 D8) and follows the {@code /api/v1}
 * conventions (ADR-VEC-05): the item is named by its key, its ETag is its version, a change to an
 * existing item takes that version back as {@code If-Match}, and a refusal is a problem document.
 * The list of a workspace's items is {@link WorkspacesResource#items}.
 *
 * <p>Each write records its origin on the event it appends: the client's {@code Vectis-Origin} tag
 * if it sends one, so that a tab can tell its own writes from remote ones, and otherwise
 * {@value #CHANNEL}, this channel's name.
 */
@Path("/api/v1/workspaces/{key}/items")
@Produces(MediaType.APPLICATION_JSON)
public class ItemsResource {

    /** The origin of a write from a client that does not name itself. */
    static final String CHANNEL = "api";

    static final String ORIGIN_HEADER = "Vectis-Origin";

    private static final Set<String> CREATE = Set.of("title", "board", "column", "rank", "fields");
    private static final Set<String> EDIT = Set.of("title", "fields");
    private static final Set<String> MOVE = Set.of("column", "rank", "fields");

    private final ItemCommands commands;
    private final WorkspaceRepository workspaces;
    private final ItemRepository items;
    private final ObjectMapper json;

    public ItemsResource(ItemCommands commands, WorkspaceRepository workspaces, ItemRepository items,
                         ObjectMapper json) {
        this.commands = commands;
        this.workspaces = workspaces;
        this.items = items;
        this.json = json;
    }

    /** The item, with its version as the ETag. */
    @GET
    @Path("/{item}")
    public Uni<Response> get(@PathParam("key") String key, @PathParam("item") String item) {
        return workspaces.findSummary(key)
                .map(found -> found.orElseThrow(() -> ProblemException.notFound("workspace " + key + " does not exist")))
                .chain(ws -> items.findPlaced(ws.id(), item))
                .map(found -> found.orElseThrow(() -> ProblemException.notFound("workspace " + key + " has no item "
                        + item)))
                .map(placed -> ok(placed).build());
    }

    /**
     * Creates an item from {@code {title, board?, column?, rank?, fields?}}: 201, with the item, its
     * {@code Location} and its ETag.
     */
    @POST
    public Uni<Response> create(@PathParam("key") String key, @HeaderParam(HttpHeaders.CONTENT_TYPE) String type,
                                @HeaderParam(ORIGIN_HEADER) String origin, String body, @Context UriInfo uri) {
        ObjectNode request = ItemRequests.object(json, type, body, CREATE);
        CreateItem command = new CreateItem(key, ItemRequests.text(request, "board"),
                ItemRequests.text(request, "column"), ItemRequests.text(request, "title"),
                ItemRequests.text(request, "rank"), ItemRequests.object(json, request, "fields"), origin(origin));
        return commands.create(command)
                .map(placed -> Response.created(uri.getAbsolutePathBuilder().path(placed.item().key()).build())
                        .entity(view(placed)).tag(tag(placed)).build());
    }

    /**
     * Edits the item's title and fields from {@code {title?, fields?}}, {@code fields} being a JSON
     * merge patch (RFC 7396): a field set to null is removed.
     */
    @PATCH
    @Path("/{item}")
    public Uni<Response> edit(@PathParam("key") String key, @PathParam("item") String item,
                              @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                              @HeaderParam(HttpHeaders.CONTENT_TYPE) String type,
                              @HeaderParam(ORIGIN_HEADER) String origin, String body) {
        Long version = ItemRequests.version(ifMatch);
        ObjectNode request = ItemRequests.object(json, type, body, EDIT);
        EditItem command = new EditItem(key, item, version, ItemRequests.text(request, "title"),
                ItemRequests.object(json, request, "fields"), origin(origin));
        return commands.edit(command).map(placed -> ok(placed).build());
    }

    /**
     * Moves the item to a column of its board, from {@code {column, rank?, fields?}}: {@code fields}
     * is a JSON merge patch written with the move, for what the target state needs.
     */
    @POST
    @Path("/{item}/move")
    public Uni<Response> move(@PathParam("key") String key, @PathParam("item") String item,
                              @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                              @HeaderParam(HttpHeaders.CONTENT_TYPE) String type,
                              @HeaderParam(ORIGIN_HEADER) String origin, String body) {
        Long version = ItemRequests.version(ifMatch);
        ObjectNode request = ItemRequests.object(json, type, body, MOVE);
        MoveItem command = new MoveItem(key, item, version, ItemRequests.text(request, "column"),
                ItemRequests.text(request, "rank"), ItemRequests.object(json, request, "fields"), origin(origin));
        return commands.move(command).map(placed -> ok(placed).build());
    }

    /** Deletes the item: 204. */
    @DELETE
    @Path("/{item}")
    public Uni<Response> delete(@PathParam("key") String key, @PathParam("item") String item,
                                @HeaderParam(HttpHeaders.IF_MATCH) String ifMatch,
                                @HeaderParam(ORIGIN_HEADER) String origin) {
        DeleteItem command = new DeleteItem(key, item, ItemRequests.version(ifMatch), origin(origin));
        return commands.delete(command).map(done -> Response.noContent().build());
    }

    private static String origin(String header) {
        return header == null ? CHANNEL : header;
    }

    private static Response.ResponseBuilder ok(PlacedItem placed) {
        return Response.ok(view(placed)).tag(tag(placed));
    }

    private static EntityTag tag(PlacedItem placed) {
        return new EntityTag(Long.toString(placed.item().version()));
    }

    private static ItemView view(PlacedItem placed) {
        return ItemView.of(placed);
    }
}
