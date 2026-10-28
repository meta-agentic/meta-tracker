// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ApiTestProfile} with the vault import pointed at a scratch directory that
 * {@link VaultImportApiTest} fills with a copy of this repository's own {@code .vault}, so a
 * test can edit the vault without touching the repository's.
 */
public class ImportTestProfile extends ApiTestProfile {

    /** A fixed place under {@code target/}: the profile and the test are loaded by different class loaders. */
    static final Path VAULT = Path.of("target", "vault-import-test").toAbsolutePath();

    @Override
    public Map<String, String> getConfigOverrides() {
        var overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("vectis.import.vault.root", VAULT.toString());
        return overrides;
    }
}
