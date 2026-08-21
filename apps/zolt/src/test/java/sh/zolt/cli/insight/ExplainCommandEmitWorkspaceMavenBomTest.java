package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** : reactor-internal Maven BOM imports are not emitted as external platforms. */
final class ExplainCommandEmitWorkspaceMavenBomTest {
    @TempDir
    private Path tempDir;

    @Test
    void mavenInternalBomWorkspaceBundleResolvesWhenSplitOut() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.google.code.gson", "gson", "2.10.1", """
                    <project>
                      <groupId>com.google.code.gson</groupId>
                      <artifactId>gson</artifactId>
                      <version>2.10.1</version>
                    </project>
                    """);
            writeMavenInternalBomReactor("1.0.0");

            CommandResult explain = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");
            assertEquals(0, explain.exitCode(), () -> explain.stderr());
            assertFalse(explain.stdout().contains("\"com.acme:acme-bom\" ="),
                    () -> "reactor-internal BOM must not be emitted as a live platform:\n" + explain.stdout());

            Map<String, String> documents = splitDocuments(explain.stdout());
            documents.put("workspace", withCentralMirror(documents.get("workspace"), repository));
            writeDocuments(tempDir, documents);

            CommandResult resolve = execute(
                    "resolve",
                    "--workspace",
                    "--cwd",
                    tempDir.toString(),
                    "--cache-root",
                    tempDir.resolve("cache").toString());
            assertEquals(0, resolve.exitCode(), () -> resolve.stdout() + resolve.stderr());
            ZoltLockfile lockfile = new ZoltLockfileReader().read(tempDir.resolve("zolt.lock"));
            assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                    lockPackage.packageId().equals(new PackageId("com.google.code.gson", "gson"))
                            && lockPackage.version().equals("2.10.1")));
        }
    }

    private void writeMavenInternalBomReactor(String version) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-parent</artifactId>
                  <version>%s</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <modules>
                    <module>acme-bom</module>
                    <module>app</module>
                  </modules>
                </project>
                """.formatted(version));
        writeMavenModule("acme-bom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>acme-parent</artifactId>
                    <version>%s</version>
                  </parent>
                  <artifactId>acme-bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.google.code.gson</groupId>
                        <artifactId>gson</artifactId>
                        <version>2.10.1</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(version));
        writeMavenModule("app", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>acme-parent</artifactId>
                    <version>%s</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.acme</groupId>
                        <artifactId>acme-bom</artifactId>
                        <version>${project.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.code.gson</groupId>
                      <artifactId>gson</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version));
    }

    /**
     * The final language never authors {@code [repositories]} in a draft, and a workspace member may
     * not declare them at all, so the hermetic test repository becomes an explicit Central mirror on
     * the workspace root every member inherits.
     */
    private static String withCentralMirror(String manifest, CliTestRepository repository) {
        return manifest + """

                [repositories]
                central = "%s"
                """.formatted(repository.baseUri());
    }

    private void writeMavenModule(String name, String pom) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), pom);
    }

    private static void writeDocuments(Path root, Map<String, String> documents) throws IOException {
        Files.writeString(root.resolve("zolt.toml"), documents.get("workspace"));
        for (Map.Entry<String, String> document : documents.entrySet()) {
            if ("workspace".equals(document.getKey())) {
                continue;
            }
            Path member = root.resolve(document.getKey());
            Files.createDirectories(member);
            Files.writeString(member.resolve("zolt.toml"), document.getValue());
        }
    }

    private static Map<String, String> splitDocuments(String bundle) {
        Map<String, String> documents = new LinkedHashMap<>();
        String key = null;
        StringBuilder body = new StringBuilder();
        for (String line : bundle.split("\n", -1)) {
            String header = documentKey(line);
            if (header != null) {
                if (key != null) {
                    documents.put(key, body.toString());
                }
                key = header;
                body = new StringBuilder();
                continue;
            }
            if (key != null) {
                body.append(line).append('\n');
            }
        }
        if (key != null) {
            documents.put(key, body.toString());
        }
        return documents;
    }

    private static String documentKey(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("# ---") || !trimmed.endsWith("---")) {
            return null;
        }
        if (trimmed.contains("workspace root")) {
            return "workspace";
        }
        String inner = trimmed.substring("# ---".length(), trimmed.length() - "---".length()).strip();
        return inner.endsWith("/zolt.toml") ? inner.substring(0, inner.length() - "/zolt.toml".length()) : inner;
    }
}
