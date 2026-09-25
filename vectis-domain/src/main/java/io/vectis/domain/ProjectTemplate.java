package io.vectis.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The built-in starting layouts a workspace can be provisioned from.
 *
 * <p>Provisioning <em>copies</em>: {@link #instantiate} lays the template's columns out as
 * an ordinary {@link Board} with fresh identities, and nothing afterwards remembers which
 * template it came from. That is deliberately the smallest model. R-CORE-5 asks for an
 * instance that references its template and carries only overrides, so that template
 * evolution is inheritable; those semantics belong to the VEC-45 spike and ADR-VEC-03,
 * and a copy is the one choice that does not pre-empt either answer.
 *
 * <p>{@code issueTypes} are the values an item's {@code fields.type} is expected to carry.
 * They are catalogue metadata only and are not persisted per workspace.
 */
public enum ProjectTemplate {

    KANBAN("kanban", "Kanban",
            List.of("To Do", "In Progress", "Done"),
            List.of("task", "bug", "epic")),

    SCRUM("scrum", "Scrum",
            List.of("Backlog", "To Do", "In Progress", "In Review", "Done"),
            List.of("story", "task", "bug", "epic"));

    /** The {@code fields.type} value that marks an item as an epic. */
    public static final String EPIC_ISSUE_TYPE = "epic";

    private final String id;
    private final String displayName;
    private final List<String> columnNames;
    private final List<String> issueTypes;

    ProjectTemplate(String id, String displayName, List<String> columnNames, List<String> issueTypes) {
        this.id = id;
        this.displayName = displayName;
        this.columnNames = List.copyOf(columnNames);
        this.issueTypes = List.copyOf(issueTypes);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> columnNames() {
        return columnNames;
    }

    public List<String> issueTypes() {
        return issueTypes;
    }

    /** Exact, case-sensitive match on the wire id. */
    public static Optional<ProjectTemplate> byId(String id) {
        for (ProjectTemplate template : values()) {
            if (template.id.equals(id)) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }

    /** A new board for {@code workspaceId} holding this template's columns in order. */
    public Board instantiate(UUID workspaceId) {
        List<BoardColumn> columns = IntStream.range(0, columnNames.size())
                .mapToObj(i -> BoardColumn.create(columnNames.get(i), i))
                .toList();
        return Board.create(workspaceId, displayName + " board", columns);
    }
}
