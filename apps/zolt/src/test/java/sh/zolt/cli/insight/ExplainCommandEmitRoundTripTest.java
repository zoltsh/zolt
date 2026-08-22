package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.toml.manifest.write.ManifestCanonicalWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every document {@code zolt explain --emit-toml} writes is the final manifest language: it parses
 * back through the final loader, and re-writing the parsed result through the canonical writer
 * reproduces the same bytes, which is what makes the emitted draft canonical rather than merely
 * parseable (design §5, §18.4).
 */
final class ExplainCommandEmitRoundTripTest {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();
    private static final ManifestCanonicalWriter WRITER = new ManifestCanonicalWriter();

    @TempDir
    private Path tempDir;

    @Test
    void standaloneMavenDraftParsesBackThroughTheFinalLoader() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>service</artifactId>
                  <version>1.4.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson</groupId>
                        <artifactId>jackson-bom</artifactId>
                        <version>2.19.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.3.1-jre</version>
                      <exclusions>
                        <exclusion>
                          <groupId>com.google.code.findbugs</groupId>
                          <artifactId>jsr305</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.14.4</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        emit("maven");

        AuthoredManifest parsed = assertCanonical(tempDir.resolve("zolt.toml"));
        assertEquals("service", parsed.project().orElseThrow().identity().name().value());
        assertEquals(1, parsed.platforms().orElseThrow().entries().size());
        assertEquals(3, parsed.dependencies().orElseThrow().declarations().size());
    }

    @Test
    void mavenReactorWorkspaceDocumentsParseBackThroughTheFinalLoader() throws IOException {
        writeReactor();

        emit("maven");

        AuthoredManifest root = assertCanonical(tempDir.resolve("zolt.toml"));
        assertTrue(root.workspace().isPresent());
        assertTrue(root.project().isEmpty());
        assertEquals(
                "com.acme",
                root.workspace().orElseThrow().projectDefaults().orElseThrow().group().orElseThrow().value());

        AuthoredManifest member = assertCanonical(tempDir.resolve("app/zolt.toml"));
        assertEquals("app", member.project().orElseThrow().identity().name().value());
        // Shared identity is inherited from [workspace.project], never materialized in the member.
        assertTrue(member.project().orElseThrow().identity().group().isEmpty());
        assertTrue(member.project().orElseThrow().identity().version().isEmpty());
        assertCanonical(tempDir.resolve("core/zolt.toml"));
    }

    @Test
    void gradleMultiProjectDocumentsParseBackThroughTheFinalLoader() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'sales'
                include 'core'
                include 'app'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                group = 'com.acme.sales'
                version = '2.0.0'
                """);
        writeGradleSubproject("core", "");
        writeGradleSubproject("app", """
                dependencies {
                    implementation project(':core')
                    implementation 'com.google.guava:guava:33.3.1-jre'
                }
                """);

        emit("gradle");

        AuthoredManifest root = assertCanonical(tempDir.resolve("zolt.toml"));
        assertTrue(root.workspace().isPresent());
        AuthoredManifest app = assertCanonical(tempDir.resolve("app/zolt.toml"));
        assertEquals(2, app.dependencies().orElseThrow().declarations().size());
        assertCanonical(tempDir.resolve("core/zolt.toml"));
    }

    /**
     * Parses one emitted document through the final loader and asserts the canonical writer reproduces
     * it byte for byte once the draft's review comments are stripped.
     */
    private static AuthoredManifest assertCanonical(Path manifest) throws IOException {
        assertTrue(Files.isRegularFile(manifest), () -> "Missing emitted document " + manifest);
        String emitted = Files.readString(manifest);
        AuthoredManifest parsed = LOADER.document(manifest).authored();
        assertEquals(
                WRITER.write(parsed),
                withoutComments(emitted),
                () -> "Emitted draft is not canonical:\n" + emitted);
        return parsed;
    }

    /** The draft body: the review-before-use notice and review items are comment lines only. */
    private static String withoutComments(String emitted) {
        return emitted.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .dropWhile(String::isBlank)
                .reduce(new StringBuilder(), (out, line) -> out.append(line).append('\n'), (a, b) -> a)
                .toString();
    }

    private void emit(String source) {
        CommandResult result = execute(
                "explain",
                "--emit-toml",
                "--emit-toml-output", ".",
                "--cwd", tempDir.toString(),
                "--source", source);

        assertEquals(0, result.exitCode(), result::stderr);
        assertFalse(result.stdout().isBlank());
    }

    private void writeReactor() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.4.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        writeReactorMember("core", "");
        writeReactorMember("app", """
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>core</artifactId>
                      <version>1.4.0</version>
                    </dependency>
                  </dependencies>
                """);
    }

    private void writeReactorMember(String name, String dependencies) throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(directory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>platform</artifactId>
                    <version>1.4.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                %s</project>
                """.formatted(name, dependencies));
    }

    private void writeGradleSubproject(String name, String dependencies) throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(directory.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                group = 'com.acme.sales'
                version = '2.0.0'

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }

                """ + dependencies);
    }
}
