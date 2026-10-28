// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Where the vault import reads from (VEC-54). The location is configuration, never a request:
 * with no {@code vectis.import.vault.root} the import does not exist and the endpoint answers 404.
 */
@ConfigMapping(prefix = "vectis.import.vault")
public interface VaultImportConfig {

    /** The {@code .vault} directory. */
    Optional<String> root();

    /** The space under it to read. */
    @WithDefault("vec")
    String space();

    /** The name of the workspace when the import provisions it. */
    @WithDefault("Vectis")
    String workspaceName();
}
