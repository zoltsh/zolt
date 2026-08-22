package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
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

final class IdeModelServiceTest {
    private final IdeModelService service = new IdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void exportsNullSafeModelWhenProjectConfigCannotBeRead() throws IOException {
        Path projectDir = tempDir.resolve("missing-config");
        Files.createDirectories(projectDir);

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        assertNull(model.project().name());
        assertNull(model.java().version());
        assertEquals(List.of(), model.sourceRoots());
        assertEquals(List.of(), model.generatedSources());
        assertEquals(List.of(), model.resourceRoots());
        assertEquals(List.of(), model.dependencies().implementation());
        assertEquals(List.of(), model.classpaths().compile());
        assertEquals("CONFIG_UNREADABLE", model.diagnostics().getFirst().code());
        assertTrue(model.diagnostics().getFirst().message().contains("Could not read zolt.toml"));
        assertEquals("Fix zolt.toml and run zolt ide model --format json again.", model.diagnostics().getFirst().nextStep());
    }

    @Test
    void writesStableDiagnosticsForEditorImportSnapshots() throws IOException {
        Path projectDir = tempDir.resolve("diagnostics");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "diagnostics"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        String json = new IdeModelJsonWriter().write(service.export(projectDir, tempDir.resolve("cache")));

        assertTrue(json.contains("\"code\": \"LOCKFILE_MISSING\""));
        assertTrue(json.contains("\"message\": \"Could not find zolt.lock.\""));
        assertTrue(json.contains("\"nextStep\": \"Run zolt resolve.\""));
        assertTrue(json.contains("\"compile\": []"));
        assertTrue(json.contains("\"runtime\": []"));
        assertTrue(json.contains("\"test\": []"));
    }

    @Test
    void reportsInvalidProjectConfigAsStructuredDiagnostic() throws IOException {
        Path projectDir = tempDir.resolve("invalid-config");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "invalid-config
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("CONFIG_INVALID", diagnostic.code());
        assertFalse(diagnostic.message().startsWith("Could not read zolt.toml"));
        assertEquals(projectDir.resolve("zolt.toml").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals("Fix zolt.toml and run zolt ide model --format json again.", diagnostic.nextStep());
        assertNull(model.project().name());
        assertEquals(List.of(), model.sourceRoots());
    }

    @Test
    void recordsTimingAttributesForIdeModelExport() throws IOException {
        Path projectDir = tempDir.resolve("timed-export");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "timed-export"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        RecordingTimingRecorder recorder = new RecordingTimingRecorder();

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), false, false, recorder);

        assertEquals(List.of(), model.diagnostics());
        assertTrue(recorder.phases().contains("read ide project config"));
        assertTrue(recorder.phases().contains("build ide framework model"));
        assertEquals(
                Map.of(
                        "compileClasspathEntries", "0",
                        "runtimeClasspathEntries", "1",
                        "testClasspathEntries", "2"),
                recorder.attributesByPhase().get("build ide classpaths"));
        assertEquals(
                Map.of(
                        "sourceRoots", "4",
                        "resourceRoots", "2",
                        "diagnostics", "0"),
                recorder.attributesByPhase().get("assemble ide model"));
    }

    @Test
    void checkLockSucceedsForFreshEmptyLockfileWithoutDiagnostics() throws IOException {
        Path projectDir = tempDir.resolve("fresh-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "fresh-lock"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        new ResolveService().resolve(
                projectDir,
                new ManifestProjectConfigLoader().load(projectDir.resolve("zolt.toml")),
                tempDir.resolve("cache"),
                false,
                true);
        String freshLockfile = Files.readString(projectDir.resolve("zolt.lock"));

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), true, true, null);

        assertEquals(List.of(), model.diagnostics());
        assertEquals(freshLockfile, Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockReportsPolicyFailureWithoutRefreshingLockfile() throws IOException {
        Path projectDir = tempDir.resolve("policy-failure");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "policy-failure"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:blocked" = "1.0.0"

                [dependencies.policy]
                deny = [{ coordinate = "com.example:blocked", reason = "fixture" }]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_CHECK_FAILED", diagnostic.code());
        assertTrue(diagnostic.message().contains("Dependency policy excludes direct dependency `com.example:blocked`"));
        assertEquals(projectDir.resolve("zolt.lock").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals("Run zolt resolve.", diagnostic.nextStep());
        assertEquals("version = 7\n", Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockReportsUnsupportedLegacyLockWithoutRefreshingLockfile() throws IOException {
        Path projectDir = tempDir.resolve("legacy-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "legacy-lock"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 5\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_UNREADABLE", diagnostic.code());
        assertTrue(diagnostic.message().contains("version 5 is older than this Zolt supports (current 7)"));
        assertEquals("Run zolt resolve.", diagnostic.nextStep());
        assertEquals("version = 5\n", Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockReportsStaleProjectLockWithoutRefreshingLockfile() throws IOException {
        Path projectDir = tempDir.resolve("stale-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "stale-lock"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_STALE", diagnostic.code());
        assertTrue(diagnostic.message().contains("zolt.lock is out of date"));
        assertEquals(projectDir.resolve("zolt.lock").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals("Run zolt resolve.", diagnostic.nextStep());
        assertEquals("version = 7\n", Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockOfflineReportsUnavailableCacheWithoutRefreshingLockfile() throws IOException {
        Path projectDir = tempDir.resolve("offline-cache-miss");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "offline-cache-miss"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:missing" = "1.0.0"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_CHECK_UNAVAILABLE", diagnostic.code());
        assertTrue(diagnostic.message().contains("Offline mode requires cached POM"));
        assertEquals(projectDir.resolve("zolt.lock").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals(
                "Run zolt resolve without --offline to seed the cache, then retry zolt ide model --offline.",
                diagnostic.nextStep());
        assertEquals("version = 7\n", Files.readString(projectDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockSkipsFreshnessWhenProjectConfigCannotBeRead() throws IOException {
        Path projectDir = tempDir.resolve("missing-config-check-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), true, true);

        assertEquals(1, model.diagnostics().size());
        assertEquals("CONFIG_UNREADABLE", model.diagnostics().getFirst().code());
        assertEquals(List.of(), model.classpaths().compile());
    }

    @Test
    void checkLockSkipsFreshnessWhenLockfileCannotBeRead() throws IOException {
        Path projectDir = tempDir.resolve("invalid-lock-check-lock");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "invalid-lock-check-lock"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = \"one\"\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"), true, true);

        assertEquals(1, model.diagnostics().size());
        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_UNREADABLE", diagnostic.code());
        assertTrue(diagnostic.message().contains("Invalid value type in zolt.lock"));
        assertEquals("Run zolt resolve.", diagnostic.nextStep());
    }

    @Test
    void exportsFrameworkProviderModelAndActionableDiagnostics() throws IOException {
        Path projectDir = tempDir.resolve("framework-provider");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "framework-provider"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        IdeModelService frameworkService = new IdeModelService((root, cacheRoot, config, diagnostics) -> {
            diagnostics.add(new IdeModel.Diagnostic(
                    "warning",
                    "QUARKUS_AUGMENTATION_MISSING",
                    "Quarkus augmentation output is missing.",
                    root.resolve("target/quarkus/zolt-augmentation.properties"),
                    "Run zolt build."));
            return new IdeModel.FrameworkInfo(new IdeModel.QuarkusInfo(
                    true,
                    "fast-jar",
                    "missing",
                    "input-sha",
                    null,
                    root.resolve("target/quarkus/zolt-augmentation.properties"),
                    root.resolve("target/quarkus"),
                    root.resolve("target/quarkus-app"),
                    root.resolve("target/quarkus-app/quarkus-run.jar"),
                    root.resolve("target/quarkus-app/quarkus/generated-bytecode.jar"),
                    root.resolve("target/quarkus-app/quarkus/transformed-bytecode.jar"),
                    List.of(new IdeModel.QuarkusGeneratedOutput(
                            "runner-jar",
                            "runner-jar",
                            root.resolve("target/quarkus-app/quarkus-run.jar"),
                            false)),
                    List.of(root.resolve("target/quarkus-deployment/quarkus-rest-deployment.jar"))));
        });

        IdeModel model = frameworkService.export(projectDir, tempDir.resolve("cache"));

        assertTrue(model.frameworks().quarkus().enabled());
        assertEquals("missing", model.frameworks().quarkus().augmentationStatus());
        assertEquals(
                List.of(new IdeModel.QuarkusGeneratedOutput(
                        "runner-jar",
                        "runner-jar",
                        projectDir.toAbsolutePath().normalize().resolve("target/quarkus-app/quarkus-run.jar"),
                        false)),
                model.frameworks().quarkus().generatedOutputs());
        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("QUARKUS_AUGMENTATION_MISSING", diagnostic.code());
        assertEquals("Run zolt build.", diagnostic.nextStep());
    }

    @Test
    void escapingConfiguredPathsBecomeDiagnosticsInsteadOfIdeModelPaths() throws IOException {
        Path projectDir = tempDir.resolve("unsafe-paths");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "outside-artifact"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["../outside-source"]

                [resources]
                main = ["../outside-resources"]

                [generated.main.api]
                kind = "declared-root"
                output = "../outside-generated"
                inputs = ["../outside-openapi.yaml"]
                required = false
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));
        String json = new IdeModelJsonWriter().write(model);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("CONFIG_INVALID", diagnostic.code());
        assertTrue(diagnostic.message().contains("build.sources"));
        assertTrue(diagnostic.message().contains("omit empty, `.` and `..` segments"));
        assertEquals(projectDir.resolve("zolt.toml").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals(List.of(), model.sourceRoots());
        assertEquals(List.of(), model.generatedSources());
        assertEquals(List.of(), model.resourceRoots());
        assertEquals(null, model.outputs().mainClasses());
        assertEquals(null, model.outputs().packagePath());
        assertEquals(null, model.packageInfo().mainJar());
        assertTrue(json.contains("\"mainClasses\": null"));
        assertTrue(json.contains("\"package\": null"));
        assertTrue(json.contains("\"mainJar\": null"));
        assertTrue(json.contains("\"code\": \"CONFIG_INVALID\""));
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
