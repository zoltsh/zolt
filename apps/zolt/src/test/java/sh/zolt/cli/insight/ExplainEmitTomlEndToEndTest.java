package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.insight.EmitTomlManifests.constraintVersion;
import static sh.zolt.cli.insight.EmitTomlManifests.declarations;
import static sh.zolt.cli.insight.EmitTomlManifests.dependency;
import static sh.zolt.cli.insight.EmitTomlManifests.fixedVersion;
import static sh.zolt.cli.insight.EmitTomlManifests.has;
import static sh.zolt.cli.insight.EmitTomlManifests.parse;
import static sh.zolt.cli.insight.EmitTomlManifests.platformVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.authored.AuthoredManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end {@code zolt explain --emit-toml} drafts: a property-driven POM and a
 * dependencyManagement POM are emitted, parsed back through the final loader, and then actually
 * resolved, so the draft is proven adoptable rather than merely well-formed.
 */
final class ExplainEmitTomlEndToEndTest {
    @TempDir
    private Path tempDir;

    private static final String MAVEN_PROPERTY_POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme.widgets</groupId>
              <artifactId>widget-catalog</artifactId>
              <version>2.3.1</version>
              <name>Widget Catalog</name>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
                <jackson.version>2.17.1</jackson.version>
                <guava.version>33.2.1-jre</guava.version>
                <junit.version>5.10.2</junit.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.junit</groupId>
                    <artifactId>junit-bom</artifactId>
                    <version>${junit.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>com.fasterxml.jackson.core</groupId>
                  <artifactId>jackson-databind</artifactId>
                  <version>${jackson.version}</version>
                </dependency>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>${guava.version}</version>
                  <exclusions>
                    <exclusion>
                      <groupId>com.google.code.findbugs</groupId>
                      <artifactId>jsr305</artifactId>
                    </exclusion>
                    <exclusion>
                      <groupId>org.checkerframework</groupId>
                      <artifactId>checker-qual</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final String MAVEN_MANAGED_POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>managed-demo</artifactId>
              <version>1.0.0</version>
              <properties>
                <maven.compiler.release>21</maven.compiler.release>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.junit</groupId>
                    <artifactId>junit-bom</artifactId>
                    <version>5.10.2</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                  <dependency>
                    <groupId>org.apiguardian</groupId>
                    <artifactId>apiguardian-api</artifactId>
                    <version>1.1.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
                <dependency>
                  <groupId>org.jacoco</groupId>
                  <artifactId>org.jacoco.agent</artifactId>
                  <version>0.8.12</version>
                  <classifier>runtime</classifier>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void emitTomlInterpolatesPropertyVersionsEmitsCoordsAndExclusions() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), MAVEN_PROPERTY_POM);

        CommandResult result = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        String toml = result.stdout();

        // : real project coordinates, no cannot-read comment.
        assertTrue(toml.contains("group = \"com.acme.widgets\""), () -> toml);
        assertTrue(toml.contains("version = \"2.3.1\""), () -> toml);
        assertFalse(toml.contains("could not be read"), () -> toml);

        // : interpolated concrete versions; no interpolation token survives.
        assertTrue(toml.contains("\"com.fasterxml.jackson.core:jackson-databind\" = \"2.17.1\""), () -> toml);
        assertTrue(toml.contains("\"org.junit:junit-bom\" = \"5.10.2\""), () -> toml);
        assertTrue(toml.contains("\"org.junit.jupiter:junit-jupiter\" = { managed = true }"), () -> toml);
        assertFalse(toml.contains("${"), () -> "no interpolation token should survive:\n" + toml);

        // : guava's exclusions carried in the draft as an `exclude` coordinate array.
        assertTrue(toml.contains("\"com.google.guava:guava\" = { version = \"33.2.1-jre\", exclude = ["), () -> toml);
        assertTrue(
                toml.contains("exclude = [\"com.google.code.findbugs:jsr305\", "
                        + "\"org.checkerframework:checker-qual\"]"),
                () -> toml);
    }

    @Test
    void emitTomlWithPropertyVersionsRoundTripsThroughTheFinalLoader() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), MAVEN_PROPERTY_POM);

        CommandResult result = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");
        assertEquals(0, result.exitCode());

        AuthoredManifest parsed = parse(result.stdout());

        assertEquals("com.acme.widgets", parsed.project().orElseThrow().identity().group().orElseThrow().value());
        assertEquals("2.3.1", parsed.project().orElseThrow().identity().version().orElseThrow().value());
        assertEquals(
                "2.17.1",
                fixedVersion(parsed, DependencyLane.IMPLEMENTATION, "com.fasterxml.jackson.core:jackson-databind"));
        assertEquals(
                "33.2.1-jre",
                fixedVersion(parsed, DependencyLane.IMPLEMENTATION, "com.google.guava:guava"));
        assertEquals("5.10.2", platformVersion(parsed, "org.junit:junit-bom"));
        assertInstanceOf(
                DependencySelector.Managed.class,
                dependency(parsed, DependencyLane.TEST, "org.junit.jupiter:junit-jupiter").selector());
        assertEquals(
                List.of(
                        new DependencyCoordinate("com.google.code.findbugs:jsr305"),
                        new DependencyCoordinate("org.checkerframework:checker-qual")),
                dependency(parsed, DependencyLane.IMPLEMENTATION, "com.google.guava:guava")
                        .metadata()
                        .exclusions());
    }

    @Test
    void emitTomlCarriesDependencyManagementFactsAndResolvesDraft() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("org.junit", "junit-bom", "5.10.2", """
                    <project>
                      <groupId>org.junit</groupId>
                      <artifactId>junit-bom</artifactId>
                      <version>5.10.2</version>
                      <packaging>pom</packaging>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.2</version>
                          </dependency>
                          <dependency>
                            <groupId>org.junit.platform</groupId>
                            <artifactId>junit-platform-console</artifactId>
                            <version>1.11.4</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);
            repository.addArtifact("org.junit.jupiter", "junit-jupiter", "5.10.2", """
                    <project>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.2</version>
                      <dependencies>
                        <dependency>
                          <groupId>org.apiguardian</groupId>
                          <artifactId>apiguardian-api</artifactId>
                          <version>1.1.2</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """);
            repository.addArtifact("org.apiguardian", "apiguardian-api", "1.1.0", """
                    <project>
                      <groupId>org.apiguardian</groupId>
                      <artifactId>apiguardian-api</artifactId>
                      <version>1.1.0</version>
                    </project>
                    """);
            repository.addArtifact("org.apiguardian", "apiguardian-api", "1.1.2", """
                    <project>
                      <groupId>org.apiguardian</groupId>
                      <artifactId>apiguardian-api</artifactId>
                      <version>1.1.2</version>
                    </project>
                    """);
            repository.addArtifact("org.junit.platform", "junit-platform-console", "1.11.4", """
                    <project>
                      <groupId>org.junit.platform</groupId>
                      <artifactId>junit-platform-console</artifactId>
                      <version>1.11.4</version>
                    </project>
                    """);
            Files.writeString(tempDir.resolve("pom.xml"), MAVEN_MANAGED_POM);

            CommandResult explain = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");

            assertEquals(0, explain.exitCode(), () -> explain.stderr());
            AuthoredManifest parsed = parse(explain.stdout());
            assertEquals("5.10.2", platformVersion(parsed, "org.junit:junit-bom"));
            assertInstanceOf(
                    DependencySelector.Managed.class,
                    dependency(parsed, DependencyLane.TEST, "org.junit.jupiter:junit-jupiter").selector());
            assertEquals("1.1.0", constraintVersion(parsed, "org.apiguardian:apiguardian-api"));
            assertFalse(
                    has(parsed, DependencyLane.IMPLEMENTATION, "org.jacoco:org.jacoco.agent"),
                    () -> "a classifier dependency stays a review note: " + declarations(parsed));
            assertTrue(explain.stdout().contains("classifier `runtime`"), () -> explain.stdout());

            Files.writeString(tempDir.resolve("zolt.toml"), withCentralMirror(explain.stdout(), repository));
            CommandResult resolve = execute(
                    "resolve",
                    "--cwd", tempDir.toString(),
                    "--cache-root", tempDir.resolve("cache").toString());

            assertEquals(0, resolve.exitCode(), () -> resolve.stderr());
            String lock = Files.readString(tempDir.resolve("zolt.lock"));
            assertTrue(lock.contains("org.apiguardian:apiguardian-api:1.1.0"), () -> lock);
            assertFalse(lock.contains("org.apiguardian:apiguardian-api:1.1.2"), () -> lock);
            assertTrue(lock.contains(
                    "id = \"org.junit.platform:junit-platform-console\"\nversion = \"1.11.4\""), () -> lock);
        }
    }

    /**
     * The final language never authors {@code [repositories]} in a draft, so a draft inherits Maven
     * Central. Pointing a resolve at the hermetic test repository is an explicit Central mirror
     * appended to the emitted document.
     */
    private static String withCentralMirror(String manifest, CliTestRepository repository) {
        return manifest + """

                [repositories]
                central = "%s"
                """.formatted(repository.baseUri());
    }
}
