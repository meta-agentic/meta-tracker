package io.vectis.server.api;

import io.vectis.domain.ProjectTemplate;
import io.vectis.domain.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The body of {@code POST /api/v1/workspaces}. Every field is checked, not just the first bad one. */
public record CreateWorkspaceRequest(String key, String name, String template) {

    static final int MAX_NAME_LENGTH = 200;

    List<ApiError.Violation> violations() {
        List<ApiError.Violation> out = new ArrayList<>();
        if (!Workspace.isValidKey(key)) {
            out.add(new ApiError.Violation("key",
                    "must be 2-10 characters, upper-case letters and digits, starting with a letter"));
        }
        if (name == null || name.isBlank()) {
            out.add(new ApiError.Violation("name", "must not be blank"));
        } else if (name.length() > MAX_NAME_LENGTH) {
            out.add(new ApiError.Violation("name", "must be at most " + MAX_NAME_LENGTH + " characters"));
        }
        if (ProjectTemplate.byId(template).isEmpty()) {
            String known = Stream.of(ProjectTemplate.values()).map(ProjectTemplate::id)
                    .collect(Collectors.joining(", "));
            out.add(new ApiError.Violation("template", template == null
                    ? "is required; expected one of: " + known
                    : "unknown template '" + template + "'; expected one of: " + known));
        }
        return out;
    }
}
