// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** VEC-54: with no vault configured the import does not exist, so a deployment that never asked for it has none. */
@QuarkusTest
@TestProfile(ApiTestProfile.class)
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class VaultImportNotConfiguredTest {

    @Test
    void theVaultImportIsA404UntilItIsConfigured() {
        given().post("/api/v1/imports/vault").then().statusCode(404)
                .contentType(startsWith("application/problem+json"))
                .body("detail", equalTo("import source vault is not configured on this server"));
    }
}
