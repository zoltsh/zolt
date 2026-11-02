package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenStaticProjectInspector;

final class MavenKotlinJvmBoundarySafetyTest {
    private static final String SAFE_PROPERTIES = """
            <maven.compiler.release>21</maven.compiler.release>
            <maven.compiler.proc>none</maven.compiler.proc>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
            <kotlin.version>2.4.20</kotlin.version>
            <kapt.include.compile.classpath>false</kapt.include.compile.classpath>
            """;
    private static final String SAFE_COMPILER = """
            <plugin>
              <artifactId>maven-compiler-plugin</artifactId>
              <version>3.13.0</version>
            </plugin>
            """;

    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();
    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void requiresAnExplicitUtf8EncodingAndRejectsOtherCompilerProperties() throws IOException {
        List<PropertyCase> cases = List.of(
                new PropertyCase(
                        SAFE_PROPERTIES.replace(
                                "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>", ""),
                        "source encoding was not explicitly UTF-8"),
                new PropertyCase(
                        SAFE_PROPERTIES.replace(
                                "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                                "<project.build.sourceEncoding>UTF-16</project.build.sourceEncoding>"),
                        "source encoding was not explicitly UTF-8"),
                new PropertyCase(
                        SAFE_PROPERTIES.replace(
                                "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                                "<project.build.sourceEncoding>${source.encoding}</project.build.sourceEncoding>"),
                        "source encoding was not explicitly UTF-8"),
                new PropertyCase(
                        SAFE_PROPERTIES + "<maven.compiler.parameters>true</maven.compiler.parameters>",
                        "compiler-control properties"),
                new PropertyCase(SAFE_PROPERTIES + "<encoding>UTF-8</encoding>", "compiler-control properties"),
                new PropertyCase(SAFE_PROPERTIES + "<maven.main.skip>false</maven.main.skip>",
                        "compiler-control properties"),
                new PropertyCase(SAFE_PROPERTIES + "<maven.test.skip>false</maven.test.skip>",
                        "compiler-control properties"));

        int index = 0;
        for (PropertyCase value : cases) {
            Path root = project("compiler-property-" + index++);
            mainKotlin(root);
            writePom(root, pom("kotlin-boundary", value.properties(), "", SAFE_COMPILER));
            assertNotEmitted(draft(root), value.reason());
        }

        Path alias = project("utf8-alias");
        mainKotlin(alias);
        writePom(alias, pom(
                "kotlin-boundary",
                SAFE_PROPERTIES.replace(">UTF-8<", ">UTF8<"),
                "",
                SAFE_COMPILER));
        assertEmitted(draft(alias));
    }

    @Test
    void requiresOnePlainFixedCompilerPluginFromTheSupportedLine() throws IOException {
        List<PluginCase> cases = List.of(
                new PluginCase("", false),
                new PluginCase(compiler("3.12.1", ""), false),
                new PluginCase(compiler("4.0.0-beta-5", ""), false),
                new PluginCase(compiler("${compiler.version}", ""), false),
                new PluginCase(compiler("3.13.0", "<configuration><parameters>true</parameters></configuration>"),
                        false),
                new PluginCase(compiler("3.13.0", """
                        <dependencies><dependency>
                          <groupId>com.example</groupId><artifactId>compiler-extension</artifactId>
                          <version>1.0.0</version>
                        </dependency></dependencies>
                        """), false),
                new PluginCase(compiler("3.13.0", ""), true),
                new PluginCase(compiler("3.16.0", ""), true));

        int index = 0;
        for (PluginCase value : cases) {
            Path root = project("compiler-plugin-" + index++);
            mainKotlin(root);
            writePom(root, pom("kotlin-boundary", SAFE_PROPERTIES, "", value.plugin()));
            if (value.accepted()) {
                assertEmitted(draft(root));
            } else {
                assertNotEmitted(draft(root), "plain fixed maven-compiler-plugin 3.13+");
            }
        }

        Path managed = project("managed-compiler-plugin");
        mainKotlin(managed);
        writePom(managed, pom(
                "kotlin-boundary",
                SAFE_PROPERTIES,
                "<pluginManagement><plugins>" + SAFE_COMPILER + "</plugins></pluginManagement>",
                ""));
        assertEmitted(draft(managed));
    }

    @Test
    void rejectsUnsupportedLanguagePluginsEvenWhenTheirSourcesAreNotDiscovered() throws IOException {
        Path root = project("scala-plugin");
        mainKotlin(root);
        Path scalaRoot = Files.createDirectories(root.resolve("src/main/scala"));
        Files.writeString(scalaRoot.resolve("Companion.scala"), "class Companion\n");
        String scalaPlugin = """
                <plugin>
                  <groupId>net.alchim31.maven</groupId>
                  <artifactId>scala-maven-plugin</artifactId>
                  <version>4.9.6</version>
                  <executions><execution>
                    <goals><goal>compile</goal><goal>testCompile</goal></goals>
                  </execution></executions>
                </plugin>
                """;
        writePom(root, pom(
                "kotlin-boundary",
                SAFE_PROPERTIES,
                "",
                SAFE_COMPILER + scalaPlugin));

        assertNotEmitted(draft(root), "unsupported language compiler plugin");
    }

    @Test
    void rejectsSourceRootAndNestedDirectoryLinks() throws IOException {
        Path main = project("linked-main-root");
        Path linkedMain = Files.createDirectories(main.resolve("linked-main"));
        Files.writeString(linkedMain.resolve("Main.kt"), "class Main\n");
        assumeTrue(symbolicLink(main.resolve("src/main/kotlin"), linkedMain));
        writePom(main, pom("kotlin-boundary", SAFE_PROPERTIES, "", SAFE_COMPILER));
        assertNotEmitted(draft(main), "symbolic links");

        Path test = project("linked-test-root");
        Path linkedTest = Files.createDirectories(test.resolve("linked-test"));
        Files.writeString(linkedTest.resolve("DemoTest.kt"), "class DemoTest\n");
        assumeTrue(symbolicLink(test.resolve("src/test/kotlin"), linkedTest));
        writePom(test, pom("kotlin-boundary", SAFE_PROPERTIES, "", SAFE_COMPILER));
        assertNotEmitted(draft(test), "symbolic links");

        Path nested = project("linked-nested-directory");
        mainKotlin(nested);
        Path linkedPackage = Files.createDirectories(nested.resolve("linked-package"));
        Files.writeString(linkedPackage.resolve("Nested.kt"), "class Nested\n");
        assumeTrue(symbolicLink(nested.resolve("src/main/kotlin/linked"), linkedPackage));
        writePom(nested, pom("kotlin-boundary", SAFE_PROPERTIES, "", SAFE_COMPILER));
        assertNotEmitted(draft(nested), "symbolic links");

        Path linkedModule = project("linked-module-info");
        mainKotlin(linkedModule);
        Path moduleTarget = linkedModule.resolve("linked-module-info.java");
        Files.writeString(moduleTarget, "module linked.example {}\n");
        assumeTrue(symbolicLink(
                linkedModule.resolve("src/main/kotlin/module-info.java"), moduleTarget));
        writePom(linkedModule, pom("kotlin-boundary", SAFE_PROPERTIES, "", SAFE_COMPILER));
        assertNotEmitted(draft(linkedModule), "symbolic links");
    }

    @Test
    void treatsOutsideAndBackslashSourceRootsAsUnsafe() throws IOException {
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("Outside.java"), "class Outside {}\n");
        Path external = project("external-source-root");
        mainKotlin(external);
        writePom(external, pom(
                "kotlin-boundary",
                SAFE_PROPERTIES,
                "<sourceDirectory>../outside</sourceDirectory>",
                SAFE_COMPILER));
        MavenInspectionResult inspection = inspector.inspect(external);
        assertTrue(inspection.projects().getFirst().sourceLinksPresent());
        assertNotEmitted(mapper.fromMaven(inspection), "source layout was not conventional");

        Path windows = project("windows-separators");
        mainKotlin(windows);
        writePom(windows, pom(
                "kotlin-boundary",
                SAFE_PROPERTIES,
                "<sourceDirectory>src\\main\\java</sourceDirectory>",
                SAFE_COMPILER));
        assertNotEmitted(draft(windows), "could not be represented without changing its path");
    }

    @Test
    void resolvesPropertyBackedArtifactIdentityAndRejectsUnresolvedIdentity() throws IOException {
        Path resolved = project("resolved-artifact");
        mainKotlin(resolved);
        writePom(resolved, pom(
                "${artifact.name}",
                SAFE_PROPERTIES + "<artifact.name>resolved-artifact</artifact.name>",
                "",
                SAFE_COMPILER));
        DraftZoltToml resolvedDraft = draft(resolved);
        assertEmitted(resolvedDraft);
        assertEquals(
                "resolved-artifact",
                resolvedDraft.manifest().build().compiler().orElseThrow()
                        .kotlinModule().orElseThrow());

        Path unresolved = project("unresolved-artifact");
        mainKotlin(unresolved);
        writePom(unresolved, pom("${missing.artifact}", SAFE_PROPERTIES, "", SAFE_COMPILER));
        assertNotEmitted(draft(unresolved), "artifactId was not statically proven");

        int index = 0;
        for (String artifact : List.of("nested/name", "nested\\name", "..")) {
            Path unsafe = project("unsafe-artifact-" + index++);
            mainKotlin(unsafe);
            writePom(unsafe, pom(artifact, SAFE_PROPERTIES, "", SAFE_COMPILER));
            assertNotEmitted(draft(unsafe), "artifactId was not statically proven");
        }

        Path punctuation = project("punctuation-artifact");
        mainKotlin(punctuation);
        writePom(punctuation, pom("release_1.0+meta", SAFE_PROPERTIES, "", SAFE_COMPILER));
        assertEquals(
                "release_1.0+meta",
                draft(punctuation).manifest().build().compiler().orElseThrow()
                        .kotlinModule().orElseThrow());
    }

    private DraftZoltToml draft(Path root) {
        return mapper.fromMaven(inspector.inspect(root));
    }

    private static void assertEmitted(DraftZoltToml draft) {
        assertTrue(draft.manifest().toolchains().kotlin().isPresent(), draft.notes()::toString);
    }

    private static void assertNotEmitted(DraftZoltToml draft, String reason) {
        assertTrue(draft.manifest().toolchains().kotlin().isEmpty(), draft.notes()::toString);
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains(reason)), draft.notes()::toString);
    }

    private Path project(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static void mainKotlin(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/kotlin"));
    }

    private static boolean symbolicLink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | SecurityException exception) {
            return false;
        }
    }

    private static void writePom(Path root, String pom) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom);
    }

    private static String compiler(String version, String body) {
        return """
                <plugin>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <version>%s</version>
                  %s
                </plugin>
                """.formatted(version, body);
    }

    private static String pom(
            String artifactId,
            String properties,
            String buildSettings,
            String compilerPlugin) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>%s</artifactId><version>1.0.0</version>
                  <properties>%s</properties>
                  <dependencies><dependency>
                    <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId>
                    <version>${kotlin.version}</version>
                  </dependency></dependencies>
                  <build>%s<plugins>
                    <plugin>
                      <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId>
                      <version>${kotlin.version}</version><extensions>true</extensions>
                    </plugin>
                    %s
                  </plugins></build>
                </project>
                """.formatted(artifactId, properties, buildSettings, compilerPlugin);
    }

    private record PropertyCase(String properties, String reason) {
    }

    private record PluginCase(String plugin, boolean accepted) {
    }
}
