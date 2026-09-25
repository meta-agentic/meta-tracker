package io.vectis.server.api;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.ProjectTemplate;
import io.vectis.domain.Workspace;
import io.vectis.persistence.BoardRepository;
import io.vectis.persistence.ItemRepository;
import io.vectis.persistence.WorkspaceKeyConflictException;
import io.vectis.persistence.WorkspaceProvisioningRepository;
import io.vectis.persistence.WorkspaceRepository;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Workspace provisioning and the three reads the board client hydrates from. Every
 * collection is returned bare, unpaginated.
 */
@Path("/api/v1/workspaces")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceResource {

    private final WorkspaceRepository workspaces;
    private final BoardRepository boards;
    private final ItemRepository items;
    private final WorkspaceProvisioningRepository provisioning;

    public WorkspaceResource(
            WorkspaceRepository workspaces,
            BoardRepository boards,
            ItemRepository items,
            WorkspaceProvisioningRepository provisioning) {
        this.workspaces = workspaces;
        this.boards = boards;
        this.items = items;
        this.provisioning = provisioning;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> provision(CreateWorkspaceRequest request) {
        CreateWorkspaceRequest body = request == null ? new CreateWorkspaceRequest(null, null, null) : request;
        List<ApiError.Violation> violations = body.violations();
        if (!violations.isEmpty()) {
            return Uni.createFrom().item(ApiError.validation(violations));
        }

        Workspace workspace = Workspace.create(body.key(), body.name().strip());
        Board board = ProjectTemplate.byId(body.template()).orElseThrow().instantiate(workspace.id());
        return provisioning.provision(workspace, List.of(board))
                .map(created -> Response
                        .created(URI.create("/api/v1/workspaces/" + created.key()))
                        .entity(new WireViews.ProvisionedWorkspace(
                                WireViews.WorkspaceView.of(created), List.of(WireViews.BoardView.of(board))))
                        .build())
                .onFailure(WorkspaceKeyConflictException.class)
                .recoverWithItem(failure -> ApiError.conflict("key",
                        "workspace key '" + body.key() + "' is already in use"));
    }

    @GET
    @Path("/{key}")
    public Uni<Response> get(@PathParam("key") String key) {
        return withWorkspace(key, ws -> Uni.createFrom().item(WireViews.WorkspaceView.of(ws)));
    }

    @GET
    @Path("/{key}/boards")
    public Uni<Response> boards(@PathParam("key") String key) {
        return withWorkspace(key, ws -> boards.findByWorkspace(ws.id())
                .map(list -> list.stream().map(WireViews.BoardView::of).toList()));
    }

    @GET
    @Path("/{key}/items")
    public Uni<Response> items(@PathParam("key") String key) {
        return withWorkspace(key, ws -> items.findByWorkspace(ws.id())
                .map(list -> list.stream().map(WireViews.ItemView::of).toList()));
    }

    private Uni<Response> withWorkspace(String key, Function<Workspace, Uni<?>> read) {
        return workspaces.findByKey(key).chain((Optional<Workspace> found) -> found
                .<Uni<Response>>map(ws -> read.apply(ws).map(body -> Response.ok(body).build()))
                .orElseGet(() -> Uni.createFrom().item(ApiError.notFound("no workspace with key '" + key + "'"))));
    }
}
