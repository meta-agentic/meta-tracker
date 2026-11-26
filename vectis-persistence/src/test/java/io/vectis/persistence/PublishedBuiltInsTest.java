// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Published built-in versions are immutable (VEC-68 AC3): every database that ran the migration
 * holds the version as it was, and every workspace pinned to it means exactly what it meant. So
 * a published file must never change. This list records the SHA-256 of each one as published; it
 * is append-only. A changed built-in is a new file with the next version number, appended here
 * and to {@link BuiltInTemplates#PUBLISHED}; editing a digest below to make this test pass is
 * editing a published version.
 */
class PublishedBuiltInsTest {

    private static final Map<String, String> PUBLISHED_DIGESTS = new LinkedHashMap<>();

    static {
        PUBLISHED_DIGESTS.put("base.v1", "8b8ed6f0313549ff122f5961b7b16083f2f4630e5617a93f8bcdd04323a39299");
        // Revised in place by the PO's decision for VEC-70, before any customer depended on it; was
        // 2f6e3370081ed00d07cad0f196c13fd91c7b1ed5c61f4ae93cd887f5b24df645. V8 overwrites it.
        PUBLISHED_DIGESTS.put("scrum.v1", "2fba41b2f934fe1f185a978e61783b06abe8b3ea13a217a78b6841e1ecfa200b");
        PUBLISHED_DIGESTS.put("kanban.v1", "c7ee531e9ed2a920c33c3ff0dbff321adbd1dfc04f33215902cd3a7d26230c56");
        PUBLISHED_DIGESTS.put("hybrid.v1", "29505e03a5a8ce41c5f123415e73d80658e26be200d3c3a9fb554a06e5163e9f");
        PUBLISHED_DIGESTS.put("stage-gate.v1", "6f3551dca8683239124d52777dba34d1a1a9d427051228d5615705c94d838997");
    }

    @Test
    void everyPublishedBuiltInIsListedInPublishOrder() {
        assertEquals(PUBLISHED_DIGESTS.keySet().stream().toList(), BuiltInTemplates.PUBLISHED,
                "a new built-in version is appended to both lists; a published one is never removed or reordered");
    }

    @Test
    void noPublishedBuiltInFileHasChanged() throws NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, String> e : PUBLISHED_DIGESTS.entrySet()) {
            String actual = HexFormat.of().formatHex(
                    sha256.digest(BuiltInTemplates.file(e.getKey()).getBytes(StandardCharsets.UTF_8)));
            assertEquals(e.getValue(), actual, "templates/" + e.getKey() + ".json changed after it was published;"
                    + " published versions are immutable, so the change belongs in a new version file");
        }
    }
}
