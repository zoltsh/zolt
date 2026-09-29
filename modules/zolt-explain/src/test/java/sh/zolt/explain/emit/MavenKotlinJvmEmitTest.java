package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.maven.MavenStaticProjectInspector;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.manifest.authored.AuthoredTests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenKotlinJvmEmitTest {
    private static final String VERSION = "2.4.20";
    private static final String ALIGNED_PROPERTIES = """
            <maven.compiler.release>21</maven.compiler.release>
            <maven.compiler.proc>none</maven.compiler.proc>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
            <kotlin.version>2.4.20</kotlin.version>
            <kapt.include.compile.classpath>false</kapt.include.compile.classpath>
            """;
    private static final String MAIN_STDLIB = stdlib("", "", "", "");

    @TempDir
    private Path tempDir;
    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void emitsAlignedConventionalMainAndTestKotlin() throws IOException {
        Path root = project("mixed");
        directories(root, "src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin");
        writePom(root, pom("", ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", "", "", ""));
        DraftZoltToml draft = draft(root);
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals(VERSION, draft.manifest().toolchains().kotlin().orElseThrow().version().value());
        AuthoredBuild build = draft.manifest().build().build().orElseThrow();
        assertEquals(List.of("src/main/java", "src/main/kotlin"), paths(build.sources()));
        AuthoredTests.Sources tests = draft.manifest().build().tests().orElseThrow()
                .sources().orElseThrow();
        assertTrue(tests.java().isEmpty(), "the final model supplies conventional Java tests by default");
        assertEquals(List.of("src/test/kotlin"), paths(tests.kotlin()));
        assertEquals(VERSION, subject.fixed(DependencyLane.IMPLEMENTATION)
                .get("org.jetbrains.kotlin:kotlin-stdlib"));
        AuthoredCompiler compiler = draft.manifest().build().compiler().orElseThrow();
        assertEquals("UTF8", compiler.encoding().orElseThrow());
        assertEquals("kotlin-emission", compiler.kotlinModule().orElseThrow());
        assertEquals(
                "kotlin-emission-test",
                compiler.test().orElseThrow().kotlinModule().orElseThrow());
        assertFalse(draft.notes().stream().anyMatch(note ->
                note.contains("Kotlin/JVM roots were not emitted")
                        || note.contains("cannot migrate automatically")), draft.notes()::toString);
    }

    @Test
    void emitsTestOnlyKotlinWithTestScopedRuntime() throws IOException {
        Path root = project("test-only");
        directories(root, "src/main/java", "src/test/kotlin");
        writePom(root, pom(
                "",
                ALIGNED_PROPERTIES,
                stdlib("<scope>test</scope>", "", "", ""),
                "",
                "",
                "",
                "",
                ""));

        DraftZoltToml draft = draft(root);

        assertTrue(draft.manifest().build().build().isEmpty());
        assertEquals(VERSION, draft.manifest().toolchains().kotlin().orElseThrow().version().value());
        assertEquals(
                List.of("src/test/kotlin"),
                paths(draft.manifest().build().tests().orElseThrow()
                        .sources().orElseThrow().kotlin()));
        assertEquals(VERSION, DraftManifestSubject.of(draft).fixed(DependencyLane.TEST)
                .get("org.jetbrains.kotlin:kotlin-stdlib"));
        AuthoredCompiler compiler = draft.manifest().build().compiler().orElseThrow();
        assertTrue(compiler.kotlinModule().isEmpty());
        assertEquals(
                "kotlin-emission-test",
                compiler.test().orElseThrow().kotlinModule().orElseThrow());
    }

    @Test
    void keepsManualExecutionShapesReviewOnly() throws IOException {
        Path main = project("manual-main-goal");
        directories(main, "src/main/kotlin");
        writePom(main, pom("<execution><goals><goal>test-compile</goal></goals></execution>",
                ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", "", "", ""));
        assertNotEmitted(draft(main), "unsupported controls");
        Path both = project("missing-test-goal");
        directories(both, "src/main/kotlin", "src/test/kotlin");
        writePom(both, pom("<execution><goals><goal>compile</goal></goals></execution>",
                ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", "", "", ""));
        assertNotEmitted(draft(both), "unsupported controls");
    }

    @Test
    void requiresExplicitlyAlignedReleaseSemantics() throws IOException {
        Path missing = project("maven-target-only");
        directories(missing, "src/main/kotlin");
        writePom(missing, pom(
                "",
                "<maven.compiler.target>21</maven.compiler.target><maven.compiler.proc>none</maven.compiler.proc>"
                        + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>"
                        + "<kotlin.version>2.4.20</kotlin.version>"
                        + "<kapt.include.compile.classpath>false</kapt.include.compile.classpath>",
                MAIN_STDLIB,
                "",
                "",
                "",
                "",
                ""));
        assertNotEmitted(draft(missing), "compiler-control properties");

        Path mismatch = project("pre-alignment-plugin");
        directories(mismatch, "src/main/kotlin");
        writePom(mismatch, pom(
                "",
                ALIGNED_PROPERTIES.replace("2.4.20", "2.2.20"),
                MAIN_STDLIB,
                "",
                "",
                "",
                "",
                ""));
        assertNotEmitted(draft(mismatch), "target alignment lacked");
    }

    @Test
    void rejectsTestRootReplacementAndModularKotlin() throws IOException {
        Path replaced = project("replaced-test-root");
        directories(replaced, "src/test/kotlin");
        writePom(replaced, pom(
                "",
                ALIGNED_PROPERTIES,
                stdlib("<scope>test</scope>", "", "", ""),
                "<testSourceDirectory>src/test/kotlin</testSourceDirectory>",
                "",
                "",
                "",
                ""));
        assertNotEmitted(draft(replaced), "replaced rather than extended");

        Path modular = project("modular");
        directories(modular, "src/main/java", "src/main/kotlin");
        Files.writeString(modular.resolve("src/main/java/module-info.java"), "module demo {}\n");
        writePom(modular, pom("", ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", "", "", ""));
        assertNotEmitted(draft(modular), "module-info.java");
    }
    @Test
    void rejectsUnsafeStdlibShapes() throws IOException {
        List<String> unsafe = List.of(
                stdlib("", "<optional>true</optional>", "", ""),
                stdlib("", "", "<classifier>jdk8</classifier>", ""),
                stdlib("", "", "", "<type>test-jar</type>"),
                """
                <dependency>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-stdlib-jdk8</artifactId>
                  <version>2.4.20</version>
                </dependency>
                """);
        int index = 0;
        for (String dependency : unsafe) {
            Path root = project("unsafe-stdlib-" + index++);
            directories(root, "src/main/kotlin");
            writePom(root, pom("", ALIGNED_PROPERTIES, dependency, "", "", "", "", ""));
            assertNotEmitted(draft(root), "plain JAR");
        }
    }

    @Test
    void rejectsInheritedManagedGeneratedAndConfiguredShapes() throws IOException {
        Path parent = project("parented");
        directories(parent, "src/main/kotlin");
        writePom(parent, pom(
                "",
                ALIGNED_PROPERTIES,
                MAIN_STDLIB,
                "",
                "",
                "",
                "<parent><groupId>com.example</groupId><artifactId>parent</artifactId>"
                        + "<version>1.0.0</version><relativePath/></parent>",
                ""));
        assertNotEmitted(draft(parent), "inherited Maven parent");

        Path managed = project("plugin-managed");
        directories(managed, "src/main/kotlin");
        String management = """
                <pluginManagement><plugins><plugin>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-maven-plugin</artifactId>
                  <version>2.4.20</version>
                </plugin></plugins></pluginManagement>
                """;
        writePom(managed, pom(
                "", ALIGNED_PROPERTIES, MAIN_STDLIB, management, "", "", "", ""));
        assertNotEmitted(draft(managed), "pluginManagement");

        Path configured = project("configured");
        directories(configured, "src/main/kotlin");
        writePom(configured, pom(
                "",
                ALIGNED_PROPERTIES,
                MAIN_STDLIB,
                "",
                "<configuration><languageVersion>2.4</languageVersion></configuration>",
                "",
                "",
                ""));
        assertNotEmitted(draft(configured), "unsupported controls");

        Path generated = project("generated");
        directories(generated, "src/main/kotlin");
        String exec = """
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>exec-maven-plugin</artifactId>
                  <executions><execution>
                    <phase>generate-sources</phase>
                    <goals><goal>java</goal></goals>
                    <configuration><mainClass>com.example.Generate</mainClass></configuration>
                  </execution></executions>
                </plugin>
                """;
        writePom(generated, pom(
                "", ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", exec, "", ""));
        assertNotEmitted(draft(generated), "generated source or resource steps");
    }

    @Test
    void rejectsProfilesDuplicatePluginsAndHiddenSourceBehavior() throws IOException {
        Path profiled = project("profiled");
        directories(profiled, "src/main/kotlin");
        writePom(profiled, pom(
                "",
                ALIGNED_PROPERTIES,
                MAIN_STDLIB,
                "",
                "",
                "",
                "",
                "<profiles><profile><id>conditional</id></profile></profiles>"));
        assertNotEmitted(draft(profiled), "Maven profile");

        Path duplicate = project("duplicate-plugin");
        directories(duplicate, "src/main/kotlin");
        String secondKotlin = """
                <plugin>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-maven-plugin</artifactId>
                  <version>2.4.20</version>
                  <extensions>true</extensions>
                </plugin>
                """;
        writePom(duplicate, pom(
                "", ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", secondKotlin, "", ""));
        assertNotEmitted(draft(duplicate), "exactly one active Kotlin Maven plugin");

        Path groovy = project("gmavenplus");
        directories(groovy, "src/main/kotlin");
        String gmavenplus = """
                <plugin>
                  <groupId>org.codehaus.gmavenplus</groupId>
                  <artifactId>gmavenplus-plugin</artifactId>
                  <version>4.2.1</version>
                </plugin>
                """;
        writePom(groovy, pom(
                "", ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", gmavenplus, "", ""));
        assertNotEmitted(draft(groovy), "also applied gmavenplus");

        Path helper = project("build-helper");
        directories(helper, "src/main/kotlin");
        String buildHelper = """
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>build-helper-maven-plugin</artifactId>
                  <version>3.6.1</version>
                </plugin>
                """;
        writePom(helper, pom(
                "", ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", buildHelper, "", ""));
        assertNotEmitted(draft(helper), "build-helper source-root behavior");

        Path processor = project("annotation-processor");
        directories(processor, "src/main/kotlin");
        String compiler = """
                <plugin>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>maven-compiler-plugin</artifactId>
                  <version>3.15.0</version>
                  <configuration><annotationProcessorPaths><path>
                    <groupId>com.example</groupId>
                    <artifactId>processor</artifactId>
                    <version>1.0.0</version>
                  </path></annotationProcessorPaths></configuration>
                </plugin>
                """;
        writePom(processor, pom(
                "", ALIGNED_PROPERTIES, MAIN_STDLIB, "", "", compiler, "", ""));
        assertNotEmitted(draft(processor), "annotation processor paths");
    }

    @Test
    void rejectsMissingMismatchedAndDuplicateStdlibEvidence() throws IOException {
        Path missing = project("missing-stdlib");
        directories(missing, "src/main/kotlin");
        writePom(missing, pom("", ALIGNED_PROPERTIES, "", "", "", "", "", ""));
        assertNotEmitted(draft(missing), "no direct kotlin-stdlib runtime");

        Path mismatch = project("mismatched-stdlib");
        directories(mismatch, "src/main/kotlin");
        writePom(mismatch, pom(
                "",
                ALIGNED_PROPERTIES,
                MAIN_STDLIB.replace("${kotlin.version}", "2.4.10"),
                "",
                "",
                "",
                "",
                ""));
        assertNotEmitted(draft(mismatch), "versions differed");

        Path duplicate = project("duplicate-stdlib");
        directories(duplicate, "src/main/kotlin");
        writePom(duplicate, pom(
                "",
                ALIGNED_PROPERTIES,
                MAIN_STDLIB + MAIN_STDLIB,
                "",
                "",
                "",
                "",
                ""));
        assertNotEmitted(draft(duplicate), "more than one kotlin-stdlib");
    }

    private DraftZoltToml draft(Path root) {
        return mapper.fromMaven(new MavenStaticProjectInspector().inspect(root));
    }

    private static void assertNotEmitted(DraftZoltToml draft, String reason) {
        assertTrue(draft.manifest().toolchains().kotlin().isEmpty(),
                () -> "unexpected Kotlin toolchain: " + draft.manifest().toolchains());
        assertTrue(draft.manifest().build().tests().isEmpty());
        assertTrue(draft.manifest().build().build().stream()
                .flatMap(build -> build.sources().stream())
                .noneMatch(root -> root.value().contains("kotlin")));
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains(reason)), draft.notes()::toString);
    }

    private Path project(String name) throws IOException {
        return Files.createDirectories(tempDir.resolve(name));
    }

    private static void directories(Path root, String... paths) throws IOException {
        for (String path : paths) {
            Files.createDirectories(root.resolve(path));
        }
    }

    private static void writePom(Path root, String pom) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom);
    }

    private static String pom(
            String goals,
            String properties,
            String dependency,
            String buildSettings,
            String pluginSettings,
            String extraPlugins,
            String parent,
            String profiles) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <groupId>com.example</groupId>
                  <artifactId>kotlin-emission</artifactId>
                  <version>1.0.0</version>
                  <properties>%s</properties>
                  <dependencies>%s</dependencies>
                  <build>
                    %s
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>${kotlin.version}</version>
                        <extensions>true</extensions>
                        %s
                        %s
                      </plugin>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.13.0</version>
                      </plugin>
                      %s
                    </plugins>
                  </build>
                  %s
                </project>
                """.formatted(
                        parent,
                        properties,
                        dependency,
                        buildSettings,
                        pluginSettings,
                        executions(goals),
                        extraPlugins,
                        profiles);
    }

    private static String executions(String goals) {
        return goals.isBlank() ? "" : "<executions>" + goals + "</executions>";
    }

    private static String stdlib(
            String scope,
            String optional,
            String classifier,
            String type) {
        return """
                <dependency>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-stdlib</artifactId>
                  <version>${kotlin.version}</version>
                  %s%s%s%s
                </dependency>
                """.formatted(scope, optional, classifier, type);
    }

    private static List<String> paths(List<ManifestRelativePath> roots) {
        return roots.stream().map(ManifestRelativePath::value).toList();
    }
}
