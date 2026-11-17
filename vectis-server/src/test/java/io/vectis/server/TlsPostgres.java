// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A PostgreSQL that serves TLS with a certificate for {@code localhost} only, signed by a CA made
 * for this run. The keys are generated with {@code openssl} at test time, so the repository carries
 * no private key. Connecting by {@code 127.0.0.1} instead of {@code localhost} is therefore a host
 * name the certificate does not name, which is how the tests provoke a host-name mismatch.
 */
final class TlsPostgres implements AutoCloseable {

    /** Same minor line as CI and deploy/k8s/components/postgres. */
    private static final String IMAGE = "postgres:16-alpine";

    final Path directory;
    final Path caCertificate;
    final PostgreSQLContainer container;

    private TlsPostgres(Path directory, PostgreSQLContainer container) {
        this.directory = directory;
        this.caCertificate = directory.resolve("ca.crt");
        this.container = container;
    }

    @SuppressWarnings("resource")
    static TlsPostgres start() {
        try {
            Path directory = Files.createTempDirectory("vectis-tls-postgres");
            generateCertificates(directory);
            // The key must belong to the server's user with mode 0600, and a copied file belongs to
            // root, so the entrypoint installs both files with the right owner before starting.
            PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withCopyFileToContainer(MountableFile.forHostPath(directory.resolve("server.crt"), 0644), "/tls/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(directory.resolve("server.key"), 0600), "/tls/server.key")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c",
                    "install -o postgres -g postgres -m 0644 /tls/server.crt /var/lib/postgresql/server.crt"
                        + " && install -o postgres -g postgres -m 0600 /tls/server.key /var/lib/postgresql/server.key"
                        + " && exec docker-entrypoint.sh \"$@\"", "--"))
                .withCommand("postgres", "-c", "fsync=off", "-c", "ssl=on",
                    "-c", "ssl_cert_file=/var/lib/postgresql/server.crt",
                    "-c", "ssl_key_file=/var/lib/postgresql/server.key");
            container.start();
            return new TlsPostgres(directory, container);
        } catch (IOException e) {
            throw new IllegalStateException("could not prepare the TLS PostgreSQL", e);
        }
    }

    int port() {
        return container.getMappedPort(5432);
    }

    String database() {
        return container.getDatabaseName();
    }

    String jdbcUrl(String host) {
        return "jdbc:postgresql://" + host + ":" + port() + "/" + database();
    }

    String reactiveUrl(String host) {
        return "postgresql://" + host + ":" + port() + "/" + database();
    }

    @Override
    public void close() {
        container.stop();
    }

    private static void generateCertificates(Path directory) throws IOException {
        Files.writeString(directory.resolve("server.ext"), String.join("\n",
            "subjectAltName=DNS:localhost",
            "basicConstraints=CA:FALSE",
            "keyUsage=digitalSignature,keyEncipherment",
            "extendedKeyUsage=serverAuth", ""));
        openssl(directory, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
            "-subj", "/CN=Vectis test CA", "-keyout", "ca.key", "-out", "ca.crt",
            "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign");
        openssl(directory, "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
            "-keyout", "server.key", "-out", "server.csr");
        openssl(directory, "x509", "-req", "-in", "server.csr", "-CA", "ca.crt", "-CAkey", "ca.key",
            "-CAcreateserial", "-days", "2", "-extfile", "server.ext", "-out", "server.crt");
    }

    private static void openssl(Path directory, String... arguments) throws IOException {
        List<String> command = new java.util.ArrayList<>(List.of("openssl"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("openssl " + String.join(" ", arguments) + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running openssl", e);
        }
    }
}
