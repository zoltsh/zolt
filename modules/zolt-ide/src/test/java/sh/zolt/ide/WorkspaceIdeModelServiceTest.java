package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceIdeModelServiceTest {
    private final WorkspaceIdeModelService service = new WorkspaceIdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void exportsWorkspaceAndMemberProjectModels() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                defaultMembers = ["apps/api"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("modules/core", "core");

        WorkspaceIdeModel model = service.export(
                tempDir.resolve("apps/api/src/main/java"),
                tempDir.resolve("cache"),
                false,
                false);

        assertEquals(1, model.schemaVersion());
        assertEquals("acme-platform", model.workspace().name());
        assertEquals(tempDir.toAbsolutePath().normalize(), model.workspace().root());
        assertEquals(tempDir.resolve("zolt-workspace.toml").toAbsolutePath().normalize(), model.workspace().config());
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), model.workspace().lockfile());
        assertEquals(List.of("apps/api", "modules/core"), model.workspace().members());
        assertEquals(List.of("apps/api"), model.workspace().defaultMembers());
        assertEquals(List.of("modules/core", "apps/api"), model.workspace().buildOrder());
        assertEquals(List.of("apps/api", "modules/core"), model.projects().stream()
                .map(WorkspaceIdeModel.ProjectModel::member)
                .toList());
        assertEquals(List.of("api", "core"), model.projects().stream()
                .map(project -> project.model().project().name())
                .toList());
        assertEquals(
                List.of(new WorkspaceIdeModel.ProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")),
                model.edges());
        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void exportsMemberModelsWithWorkspaceRootLockfileAndClasspaths() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("modules/core", "core");
        Files.writeString(tempDir.resolve("zolt.lock"), """
                version = 5

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "org.projectlombok:lombok"
                version = "1.18.42"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "org/projectlombok/lombok/1.18.42/lombok-1.18.42.jar"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "com.example:test-processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "test-processor"
                direct = true
                jar = "com/example/test-processor/1.0.0/test-processor-1.0.0.jar"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.2"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                jar = "io/quarkus/quarkus-rest-deployment/3.33.2/quarkus-rest-deployment-3.33.2.jar"
                members = ["apps/api"]
                dependencies = []
                """);

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        IdeModel apiModel = model.projects().stream()
                .filter(project -> project.member().equals("apps/api"))
                .findFirst()
                .orElseThrow()
                .model();
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), apiModel.paths().lockfile());
        assertTrue(apiModel.classpaths().compile().contains(tempDir
                .resolve("modules/core/target/classes")
                .toAbsolutePath()
                .normalize()));
        assertEquals(
                List.of(tempDir.resolve("cache/org/projectlombok/lombok/1.18.42/lombok-1.18.42.jar")
                        .toAbsolutePath()
                        .normalize()),
                apiModel.classpaths().processor());
        assertEquals(
                List.of(tempDir.resolve("cache/com/example/test-processor/1.0.0/test-processor-1.0.0.jar")
                        .toAbsolutePath()
                        .normalize()),
                apiModel.classpaths().testProcessor());
        assertEquals(
                List.of(tempDir.resolve(
                                "cache/io/quarkus/quarkus-rest-deployment/3.33.2/quarkus-rest-deployment-3.33.2.jar")
                        .toAbsolutePath()
                        .normalize()),
                apiModel.classpaths().quarkusDeployment());
        assertEquals(List.of(), apiModel.diagnostics());
        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void exportsWorkspaceEdgesInDeterministicSectionAndCoordinateOrder() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/beta", "modules/alpha", "tools/processor"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:beta" = { workspace = "modules/beta" }
                "com.acme:alpha" = { workspace = "modules/alpha" }

                [test.dependencies]
                "com.acme:beta" = { workspace = "modules/beta" }

                [annotationProcessors]
                "com.acme:processor" = { workspace = "tools/processor" }
                """);
        member("modules/beta", "beta");
        member("modules/alpha", "alpha");
        member("tools/processor", "processor");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        assertEquals(List.of(
                        new WorkspaceIdeModel.ProjectEdge("apps/api", "modules/alpha", "compile", "com.acme:alpha"),
                        new WorkspaceIdeModel.ProjectEdge("apps/api", "modules/beta", "compile", "com.acme:beta"),
                        new WorkspaceIdeModel.ProjectEdge("apps/api", "modules/beta", "test", "com.acme:beta"),
                        new WorkspaceIdeModel.ProjectEdge("apps/api", "tools/processor", "processor", "com.acme:processor")),
                model.edges());
    }

    @Test
    void recordsTimingAttributesForWorkspaceIdeExport() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("modules/core", "core");
        RecordingTimingRecorder recorder = new RecordingTimingRecorder();

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false, recorder);

        assertEquals(List.of(), model.diagnostics());
        assertEquals(
                Map.of("lockfilePresent", "true", "diagnostics", "0"),
                recorder.attributesByPhase().get("read workspace ide lock"));
        assertEquals(
                Map.of("projects", "2"),
                recorder.attributesByPhase().get("export workspace ide projects"));
        assertEquals(
                Map.of(
                        "members", "2",
                        "compileClasspathEntries", "0",
                        "runtimeClasspathEntries", "2",
                        "testClasspathEntries", "4"),
                recorder.attributesByPhase().get("plan workspace ide classpaths"));
        assertEquals(
                Map.of("edges", "1"),
                recorder.attributesByPhase().get("export workspace ide edges"));
        assertEquals(
                Map.of("projects", "2", "edges", "1", "diagnostics", "0"),
                recorder.attributesByPhase().get("assemble workspace ide model"));
        assertTrue(recorder.phases().contains("discover ide workspace"));
    }

    @Test
    void recordsTimingAttributesForMissingWorkspaceLock() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api");
        Files.delete(tempDir.resolve("zolt.lock"));
        RecordingTimingRecorder recorder = new RecordingTimingRecorder();

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false, recorder);

        assertEquals("LOCKFILE_MISSING", model.diagnostics().getFirst().code());
        assertEquals(
                Map.of("lockfilePresent", "false", "diagnostics", "1"),
                recorder.attributesByPhase().get("read workspace ide lock"));
        assertEquals(
                Map.of(
                        "members", "1",
                        "compileClasspathEntries", "0",
                        "runtimeClasspathEntries", "0",
                        "testClasspathEntries", "0"),
                recorder.attributesByPhase().get("plan workspace ide classpaths"));
    }

    @Test
    void checkLockReportsWorkspacePolicyFailureWithoutRefreshingLockfile() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:blocked" = "1.0.0"

                [dependencyPolicy]
                exclude = [{ group = "com.acme", artifact = "blocked", reason = "fixture" }]
                """);
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 6\n");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_CHECK_FAILED", diagnostic.code());
        assertTrue(diagnostic.message().contains("Dependency policy excludes direct dependency `com.acme:blocked`"));
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals("Run zolt resolve --workspace.", diagnostic.nextStep());
        assertEquals("version = 6\n", Files.readString(tempDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockAcceptsFreshWorkspaceLockWithoutDiagnostics() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api");
        new WorkspaceResolveService().resolve(tempDir, tempDir.resolve("cache"), false, true);
        String freshLockfile = Files.readString(tempDir.resolve("zolt.lock"));

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), true, true);

        assertEquals(List.of(), model.diagnostics());
        assertEquals(List.of(), model.projects().getFirst().model().diagnostics());
        assertEquals(freshLockfile, Files.readString(tempDir.resolve("zolt.lock")));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 5\n");
    }

    private void member(String path, String name) throws IOException {
        member(path, name, "");
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "21"
                %s""".formatted(name, extraToml));
        Files.writeString(member.resolve("zolt.lock"), "version = 5\n");
    }

    private static final class RecordingTimingRecorder implements IdeTimingRecorder {
        private final List<String> phases = new ArrayList<>();
        private final Map<String, Map<String, String>> attributesByPhase = new LinkedHashMap<>();

        List<String> phases() {
            return List.copyOf(phases);
        }

        Map<String, Map<String, String>> attributesByPhase() {
            return Map.copyOf(attributesByPhase);
        }

        @Override
        public void measure(String phase, Runnable action) {
            phases.add(phase);
            action.run();
        }

        @Override
        public <T> T measure(String phase, Supplier<T> action) {
            phases.add(phase);
            return action.get();
        }

        @Override
        public <T> T measure(String phase, Supplier<T> action, Function<T, Map<String, String>> attributes) {
            phases.add(phase);
            T result = action.get();
            attributesByPhase.put(phase, attributes.apply(result));
            return result;
        }
    }
}
