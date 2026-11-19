// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The command layer depends on neither JAX-RS nor an agent protocol (ADR-VEC-04 D8), so that every
 * channel, the REST resources, the agent surface and write-back, shares it on equal terms. Each row
 * is a type of one of those stacks: if a dependency, direct or transitive, brings it onto this
 * module's classpath, the build fails here.
 */
class CommandModuleIsolationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "jakarta.ws.rs.Path",                                      // JAX-RS
        "jakarta.ws.rs.core.Response",                             // JAX-RS
        "org.jboss.resteasy.reactive.server.ServerExceptionMapper", // Quarkus REST
        "io.quarkiverse.mcp.server.Tool",                          // the Quarkus MCP server extension
        "io.modelcontextprotocol.spec.McpSchema",                  // the MCP Java SDK
    })
    void isNotOnTheClasspath(String type) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(type, false, getClass().getClassLoader()));
    }
}
