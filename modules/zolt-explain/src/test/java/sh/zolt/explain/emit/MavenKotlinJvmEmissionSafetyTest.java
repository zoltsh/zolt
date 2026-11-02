package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.maven.MavenStaticProjectInspector;
import sh.zolt.manifest.ManifestRelativePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenKotlinJvmEmissionSafetyTest {
    private static final String SAFE_PROPERTIES = """
            <maven.compiler.release>21</maven.compiler.release>
            <maven.compiler.proc>none</maven.compiler.proc>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
            <kotlin.version>2.4.20</kotlin.version>
            <kapt.include.compile.classpath>false</kapt.include.compile.classpath>
            """;

    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void preservesImplicitJavaMainRootButHonorsExplicitReplacement() throws IOException {
        Path implicit = project("implicit-java");
        directory(implicit, "src/main/kotlin");
        writePom(implicit, pom(SAFE_PROPERTIES, "", ""));

        assertEquals(
                List.of("src/main/java", "src/main/kotlin"),
                mainRoots(draft(implicit)));

        Path explicit = project("explicit-main");
        directory(explicit, "src/main/kotlin");
        writePom(explicit, pom(
                SAFE_PROPERTIES,
                "<sourceDirectory>src/main/kotlin</sourceDirectory>",
                ""));

        assertEquals(List.of("src/main/kotlin"), mainRoots(draft(explicit)));
    }

    @Test
    void requiresKaptCompileClasspathDiscoveryToBeExplicitlyDisabled() throws IOException {
        List<String> properties = List.of(
                "<maven.compiler.release>21</maven.compiler.release>"
                        + "<maven.compiler.proc>none</maven.compiler.proc>"
                        + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"
                        + "<kotlin.version>2.4.20</kotlin.version>",
                SAFE_PROPERTIES.replace("false", "true"),
                "<maven.compiler.release>21</maven.compiler.release>"
                        + "<maven.compiler.proc>none</maven.compiler.proc>"
                        + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"
                        + "<kotlin.version>2.4.20</kotlin.version>"
                        + "<kapt.include.compile.classpath>${kapt.discovery}</kapt.include.compile.classpath>");
        int index = 0;
        for (String value : properties) {
            Path root = project("kapt-discovery-" + index++);
            directory(root, "src/main/kotlin");
            writePom(root, pom(value, "", ""));
            assertNotEmitted(draft(root), "KAPT compile-classpath processor discovery");
        }
    }

    @Test
    void requiresJavacClasspathProcessorDiscoveryToBeExplicitlyDisabled() throws IOException {
        List<String> properties = List.of(
                SAFE_PROPERTIES.replace("<maven.compiler.proc>none</maven.compiler.proc>", ""),
                SAFE_PROPERTIES.replace("<maven.compiler.proc>none</maven.compiler.proc>",
                        "<maven.compiler.proc>full</maven.compiler.proc>"),
                SAFE_PROPERTIES.replace("<maven.compiler.proc>none</maven.compiler.proc>",
                        "<maven.compiler.proc>${compiler.proc}</maven.compiler.proc>"));
        int index = 0;
        for (String value : properties) {
            Path root = project("javac-discovery-" + index++);
            directory(root, "src/main/kotlin");
            writePom(root, pom(value, "", ""));
            assertNotEmitted(draft(root), "javac classpath annotation processor discovery");
        }
    }

    @Test
    void requiresReleaseSemanticsForAnExplicitTestCompilerOverride() throws IOException {
        Path matchingRelease = project("matching-test-release");
        directory(matchingRelease, "src/main/kotlin");
        writePom(matchingRelease, pom(
                SAFE_PROPERTIES + "<maven.compiler.testRelease>21</maven.compiler.testRelease>",
                "",
                ""));
        assertEquals(
                List.of("src/main/java", "src/main/kotlin"),
                mainRoots(draft(matchingRelease)));

        for (String property : List.of("testTarget", "testSource")) {
            Path root = project("test-" + property);
            directory(root, "src/main/kotlin");
            writePom(root, pom(
                    SAFE_PROPERTIES + "<maven.compiler." + property + ">21</maven.compiler."
                            + property + ">",
                    "",
                    ""));
            assertNotEmitted(draft(root), "compiler-control properties");
        }
    }

    @Test
    void rejectsModuleDescriptorsFromEveryAdmittedSourceLane() throws IOException {
        for (String descriptor : List.of(
                "src/main/kotlin/module-info.java",
                "src/main/kotlin/nested/module-info.java",
                "src/test/java/module-info.java",
                "src/test/kotlin/module-info.java")) {
            Path root = project("module-" + descriptor.replace('/', '-'));
            directory(root, "src/main/kotlin");
            Path moduleInfo = root.resolve(descriptor);
            Files.createDirectories(moduleInfo.getParent());
            Files.writeString(moduleInfo, "module example {}\n");
            writePom(root, pom(SAFE_PROPERTIES, "", ""));
            assertNotEmitted(draft(root), "module-info.java");
        }
    }

    @Test
    void rejectsEveryStaticallyVisibleGeneratedOutputShape() throws IOException {
        List<String> plugins = List.of(
                """
                <plugin>
                  <artifactId>maven-antrun-plugin</artifactId>
                  <executions><execution><phase>compile</phase><goals><goal>run</goal></goals>
                    <configuration><target><mkdir dir="target/generated-sources/x"/></target></configuration>
                  </execution></executions>
                </plugin>
                """,
                """
                <plugin>
                  <groupId>org.openapitools</groupId>
                  <artifactId>openapi-generator-maven-plugin</artifactId>
                  <executions><execution><goals><goal>generate</goal></goals></execution></executions>
                </plugin>
                """,
                """
                <plugin>
                  <groupId>com.example</groupId><artifactId>schema-plugin</artifactId>
                  <executions><execution><phase>compile</phase><goals><goal>emit</goal></goals>
                    <configuration><outputDirectory>target/generated-sources/x</outputDirectory></configuration>
                  </execution></executions>
                </plugin>
                """,
                """
                <plugin>
                  <groupId>com.example</groupId><artifactId>schema-plugin</artifactId>
                  <configuration><outputDirectory>target/generated-sources/x</outputDirectory></configuration>
                  <executions><execution><phase>compile</phase><goals><goal>emit</goal></goals>
                  </execution></executions>
                </plugin>
                """,
                """
                <plugin>
                  <groupId>com.example</groupId><artifactId>resource-plugin</artifactId>
                  <executions><execution><phase>process-test-resources</phase>
                    <goals><goal>filter</goal></goals>
                  </execution></executions>
                </plugin>
                """);
        int index = 0;
        for (String plugin : plugins) {
            Path root = project("generated-" + index++);
            directory(root, "src/main/kotlin");
            writePom(root, pom(SAFE_PROPERTIES, "", plugin));
            assertNotEmitted(draft(root), "generated source or resource steps");
        }
    }

    @Test
    void rejectsCompetingExtensionsAndCompilerConfiguration() throws IOException {
        List<String> plugins = List.of(
                """
                <plugin>
                  <groupId>com.example</groupId><artifactId>lifecycle-extension</artifactId>
                  <version>1.0.0</version><extensions>true</extensions>
                </plugin>
                """,
                """
                <plugin>
                  <artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version>
                  <configuration><release>17</release></configuration>
                </plugin>
                """,
                """
                <plugin>
                  <artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version>
                  <executions><execution><goals><goal>compile</goal></goals>
                    <configuration><compilerArgs><arg>-parameters</arg></compilerArgs></configuration>
                  </execution></executions>
                </plugin>
                """,
                """
                <plugin>
                  <artifactId>maven-toolchains-plugin</artifactId><version>3.2.0</version>
                  <executions><execution><goals><goal>toolchain</goal></goals></execution></executions>
                  <configuration><toolchains><jdk><version>21</version></jdk></toolchains></configuration>
                </plugin>
                """);
        List<String> reasons = List.of(
                "another Maven lifecycle extension",
                "plain fixed maven-compiler-plugin 3.13+",
                "plain fixed maven-compiler-plugin 3.13+",
                "Maven JDK toolchain");
        for (int index = 0; index < plugins.size(); index++) {
            Path root = project("compiler-control-" + index);
            directory(root, "src/main/kotlin");
            writePom(root, pom(SAFE_PROPERTIES, "", plugins.get(index)));
            assertNotEmitted(draft(root), reasons.get(index));
        }
    }

    @Test
    void rejectsConventionalGroovyTestsWithoutChangingOrdinaryMavenDrafts() throws IOException {
        Path root = project("groovy-tests");
        directory(root, "src/main/kotlin");
        directory(root, "src/test/groovy");
        writePom(root, pom(SAFE_PROPERTIES, "", ""));

        assertNotEmitted(draft(root), "conventional Groovy test sources");
    }

    private DraftZoltToml draft(Path root) {
        return mapper.fromMaven(new MavenStaticProjectInspector().inspect(root));
    }

    private static List<String> mainRoots(DraftZoltToml draft) {
        return draft.manifest().build().build().orElseThrow().sources().stream()
                .map(ManifestRelativePath::value)
                .toList();
    }

    private static void assertNotEmitted(DraftZoltToml draft, String reason) {
        assertTrue(draft.manifest().toolchains().kotlin().isEmpty(), draft.notes()::toString);
        assertTrue(draft.manifest().build().build().stream()
                .flatMap(build -> build.sources().stream())
                .noneMatch(root -> root.value().contains("kotlin")), draft.notes()::toString);
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains(reason)), draft.notes()::toString);
    }

    private Path project(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static void directory(Path root, String path) throws IOException {
        Files.createDirectories(root.resolve(path));
    }

    private static void writePom(Path root, String pom) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom);
    }

    private static String pom(String properties, String buildSettings, String extraPlugins) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>kotlin-safety</artifactId><version>1.0.0</version>
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
                    <plugin>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>3.13.0</version>
                    </plugin>
                    %s
                  </plugins></build>
                </project>
                """.formatted(properties, buildSettings, extraPlugins);
    }
}
