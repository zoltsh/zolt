package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** : `zolt explain --emit-toml` migrates a multi-module build to a Zolt workspace. */
final class ExplainCommandEmitWorkspaceTest {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    @TempDir
    private Path tempDir;

    // --- Maven reactor -----------------------------------------------------------------------

    private void writeMavenReactor() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme.shop</groupId>
                  <artifactId>shop-parent</artifactId>
                  <version>1.4.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <jackson.version>2.17.1</jackson.version>
                    <junit.version>5.10.2</junit.version>
                  </properties>
                  <modules>
                    <module>orders-core</module>
                    <module>orders-api</module>
                  </modules>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>${jackson.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>org.junit</groupId>
                        <artifactId>junit-bom</artifactId>
                        <version>${junit.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        writeMavenModule("orders-core", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme.shop</groupId>
                    <artifactId>shop-parent</artifactId>
                    <version>1.4.0</version>
                  </parent>
                  <artifactId>orders-core</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        writeMavenModule("orders-api", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme.shop</groupId>
                    <artifactId>shop-parent</artifactId>
                    <version>1.4.0</version>
                  </parent>
                  <artifactId>orders-api</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme.shop</groupId>
                      <artifactId>orders-core</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
    }

    private void writeMavenModule(String name, String pom) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), pom);
    }

    @Test
    void mavenReactorEmitsWorkspaceBundleWithLabelledMembersAndEdge() throws IOException {
        writeMavenReactor();

        CommandResult result = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");

        assertEquals(0, result.exitCode(), () -> result.stderr());
        String toml = result.stdout();
        assertTrue(toml.contains("[workspace]"), () -> toml);
        assertTrue(toml.contains("name = \"shop-parent\""), () -> toml);
        assertTrue(toml.contains("""
                [workspace.members]
                default = ["orders-api", "orders-core"]
                include = ["orders-api", "orders-core"]
                """), () -> toml);
        // Shared identity is hoisted once so members carry only `[project] name`.
        assertTrue(toml.contains("""
                [workspace.project]
                group = "com.acme.shop"
                version = "1.4.0"
                """), () -> toml);
        assertTrue(toml.contains("# --- orders-api/zolt.toml ---"), () -> toml);
        assertTrue(toml.contains("# --- orders-core/zolt.toml ---"), () -> toml);
        // The inter-module edge is a workspace dep, not an external coordinate.
        assertTrue(toml.contains("\"com.acme.shop:orders-core\" = { workspace = true }"), () -> toml);
        // The external dep carries the inherited concrete version.
        assertTrue(toml.contains("\"com.fasterxml.jackson.core:jackson-databind\" = \"2.17.1\""), () -> toml);
        // The child module also carries the parent's imported BOM and platform-managed test dependency.
        assertTrue(toml.contains("\"org.junit:junit-bom\" = \"5.10.2\""), () -> toml);
        assertTrue(toml.contains("\"org.junit.jupiter:junit-jupiter\" = { managed = true }"), () -> toml);
        assertFalse(toml.contains("${"), () -> "no interpolation token should survive:\n" + toml);
    }

    @Test
    void mavenWorkspaceBundleRoundTripsThroughTheFinalLoader() throws IOException {
        writeMavenReactor();

        CommandResult result = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "maven");
        assertEquals(0, result.exitCode(), () -> result.stderr());

        Map<String, String> documents = splitDocuments(result.stdout());
        AuthoredManifest root = parse(documents.get("workspace"));
        AuthoredWorkspace workspace = root.workspace().orElseThrow();
        assertEquals("shop-parent", workspace.name().value());
        assertEquals(List.of("orders-api", "orders-core"), include(workspace));
        assertEquals(
                "com.acme.shop",
                workspace.projectDefaults().orElseThrow().group().orElseThrow().value());

        Map<String, AuthoredManifest> members = new LinkedHashMap<>();
        for (String member : include(workspace)) {
            AuthoredManifest parsed = parse(documents.get(member));
            members.put(member, parsed);
            assertTrue(
                    parsed.project().orElseThrow().identity().group().isEmpty(),
                    () -> "member " + member + " must inherit the workspace group, not repeat it");
        }

        AuthoredManifest api = members.get("orders-api");
        Set<String> edges = workspaceEdges(api, DependencyLane.IMPLEMENTATION);
        assertEquals(Set.of("com.acme.shop:orders-core"), edges);
        // The path is gone from the edge, so the target is proven by the coordinate a member publishes.
        assertEquals(
                "com.acme.shop:orders-core",
                coordinateOf(root, members.get("orders-core")),
                () -> "edge target must be a member: " + edges);

        AuthoredManifest core = members.get("orders-core");
        assertEquals("5.10.2", platformVersion(core, "org.junit:junit-bom"));
        assertInstanceOf(
                DependencySelector.Managed.class,
                dependency(core, DependencyLane.TEST, "org.junit.jupiter:junit-jupiter").selector(),
                () -> "parent BOM-managed test dep must be emitted as { managed = true }");
    }

    // --- Gradle multi-project ----------------------------------------------------------------

    private void writeGradleMultiProject() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'sales'
                include 'app', 'core'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [versions]
                guava = "33.4.8-jre"
                junit = "5.11.4"
                commonsLang = "3.17.0"

                [libraries]
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit" }
                commons-lang3 = { module = "org.apache.commons:commons-lang3", version.ref = "commonsLang" }
                """);
        writeGradleModule("app", """
                plugins {
                    id 'java'
                    id 'application'
                }
                sourceCompatibility = JavaVersion.VERSION_21
                dependencies {
                    implementation libs.guava
                    implementation project(':core')
                    testImplementation libs.junit.jupiter
                }
                """);
        writeGradleModule("core", """
                plugins { id 'java-library' }
                sourceCompatibility = JavaVersion.VERSION_21
                dependencies {
                    api libs.commons.lang3
                }
                """);
    }

    private void writeGradleModule(String name, String buildGradle) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("build.gradle"), buildGradle);
    }

    @Test
    void gradleMultiProjectEmitsWorkspaceBundleWithEdgeAndCatalogVersions() throws IOException {
        writeGradleMultiProject();

        CommandResult result = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "gradle");

        assertEquals(0, result.exitCode(), () -> result.stderr());
        String toml = result.stdout();
        assertTrue(toml.contains("[workspace]"), () -> toml);
        assertTrue(toml.contains("name = \"sales\""), () -> toml);
        assertTrue(toml.contains("""
                [workspace.members]
                default = ["app", "core"]
                include = ["app", "core"]
                """), () -> toml);
        assertTrue(toml.contains("# --- app/zolt.toml ---"), () -> toml);
        assertTrue(toml.contains("# --- core/zolt.toml ---"), () -> toml);
        assertTrue(toml.contains("\"com.example:core\" = { workspace = true }"), () -> toml);
        assertTrue(toml.contains("\"com.google.guava:guava\" = \"33.4.8-jre\""), () -> toml);
        assertTrue(toml.contains("\"org.apache.commons:commons-lang3\" = \"3.17.0\""), () -> toml);
    }

    @Test
    void gradleWorkspaceBundleRoundTripsThroughTheFinalLoader() throws IOException {
        writeGradleMultiProject();

        CommandResult result = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "gradle");
        assertEquals(0, result.exitCode(), () -> result.stderr());

        Map<String, String> documents = splitDocuments(result.stdout());
        AuthoredManifest root = parse(documents.get("workspace"));
        AuthoredWorkspace workspace = root.workspace().orElseThrow();
        assertEquals("sales", workspace.name().value());
        assertEquals(List.of("app", "core"), include(workspace));

        AuthoredManifest app = parse(documents.get("app"));
        assertEquals(
                "33.4.8-jre",
                fixedVersion(app, DependencyLane.IMPLEMENTATION, "com.google.guava:guava"));
        Set<String> edges = workspaceEdges(app, DependencyLane.IMPLEMENTATION);
        assertEquals(Set.of("com.example:core"), edges);

        AuthoredManifest core = parse(documents.get("core"));
        assertEquals(
                "3.17.0",
                fixedVersion(core, DependencyLane.API, "org.apache.commons:commons-lang3"));
        // The emitted edge must key by the coordinate the target member actually publishes; a
        // placeholder-group mismatch is exactly what workspace discovery would reject.
        assertEquals(
                "com.example:core",
                coordinateOf(root, core),
                () -> "emitted Gradle workspace edge must match the member coordinate: " + edges);
    }

    @Test
    void gradleWorkspaceBundleResolvesWhenSplitOut() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                rootProject.name = 'sales'
                include 'app', 'core'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        writeGradleModule("app", """
                plugins { id 'java-library' }
                sourceCompatibility = JavaVersion.VERSION_21
                dependencies {
                    implementation project(':core')
                }
                """);
        writeGradleModule("core", """
                plugins { id 'java-library' }
                sourceCompatibility = JavaVersion.VERSION_21
                """);

        CommandResult explain = execute("explain", "--emit-toml", "--cwd", tempDir.toString(), "--source", "gradle");
        assertEquals(0, explain.exitCode(), () -> explain.stderr());
        writeDocuments(tempDir, splitDocuments(explain.stdout()));

        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd",
                tempDir.toString(),
                "--cache-root",
                tempDir.resolve("cache").toString());
        assertEquals(0, resolve.exitCode(), () -> resolve.stdout() + resolve.stderr());
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * Splits the emitted multi-document bundle keyed by member path (or "workspace" for the root),
     * dropping the leading comment notice on each document so it parses on its own.
     */
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

    private static AuthoredManifest parse(String document) {
        return LOADER.document(document).authored();
    }

    private static List<String> include(AuthoredWorkspace workspace) {
        return workspace.members().include().stream().map(WorkspaceMemberPattern::value).toList();
    }

    /** The {@code group:name} a member publishes once the root's `[workspace.project]` group applies. */
    private static String coordinateOf(AuthoredManifest root, AuthoredManifest member) {
        AuthoredProjectIdentity identity = member.project().orElseThrow().identity();
        String group = identity.group()
                .map(ProjectGroup::value)
                .orElseGet(() -> root.workspace()
                        .orElseThrow()
                        .projectDefaults()
                        .orElseThrow()
                        .group()
                        .orElseThrow()
                        .value());
        return group + ":" + identity.name().value();
    }

    private static Set<String> workspaceEdges(AuthoredManifest manifest, DependencyLane lane) {
        Set<String> edges = new LinkedHashSet<>();
        for (AuthoredDependency declaration : declarations(manifest)) {
            if (declaration.lane() == lane
                    && declaration.selector() instanceof DependencySelector.Workspace) {
                edges.add(declaration.coordinate().value());
            }
        }
        return edges;
    }

    private static List<AuthoredDependency> declarations(AuthoredManifest manifest) {
        return manifest.dependencies().map(AuthoredDependencies::declarations).orElse(List.of());
    }

    private static AuthoredDependency dependency(
            AuthoredManifest manifest, DependencyLane lane, String coordinate) {
        return declarations(manifest).stream()
                .filter(entry -> entry.lane() == lane && entry.coordinate().value().equals(coordinate))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + lane + " dependency on `" + coordinate + "` in " + declarations(manifest)));
    }

    private static String fixedVersion(
            AuthoredManifest manifest, DependencyLane lane, String coordinate) {
        return assertInstanceOf(
                        DependencySelector.FixedVersion.class,
                        dependency(manifest, lane, coordinate).selector())
                .value();
    }

    private static String platformVersion(AuthoredManifest manifest, String coordinate) {
        PlatformSelector selector = manifest.platforms()
                .orElseThrow()
                .entries()
                .get(new DependencyCoordinate(coordinate));
        return assertInstanceOf(PlatformSelector.FixedVersion.class, selector).value();
    }
}
