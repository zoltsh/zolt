package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sh.zolt.build.BuildService;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.clean.CleanService;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkRunAugmenter;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class RunSnapshotConcurrencyTest {
    @TempDir
    private Path project;

    @ParameterizedTest
    @ValueSource(strings = {"classes", "thin", "uber"})
    void runningApplicationSurvivesCleanAndRebuildBeforeLazyClassAndResourceLoad(String mode) throws Exception {
        prepare();
        ProjectConfig config = config(mode);
        JavaRunner runner = new JavaRunner(java.io.File.pathSeparator, (command, outputConsumer) -> {
            Process process = null;
            try {
                try (var watcher = FileSystems.getDefault().newWatchService()) {
                    project.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
                    process = new ProcessBuilder(command).redirectErrorStream(true).start();
                    while (!Files.exists(project.resolve("ready"))) {
                        var key = watcher.poll(15, TimeUnit.SECONDS);
                        assertTrue(key != null, "application did not become ready");
                        key.pollEvents();
                        assertTrue(key.reset(), "readiness directory was removed");
                    }
                }
                // A second thread proves the lease was released before executing the application.
                var executor = Executors.newSingleThreadExecutor();
                try {
                    executor.submit(() -> {
                        new CleanService().clean(project, config);
                        Files.writeString(project.resolve("src/main/java/example/Late.java"), lateSource("changed"));
                        Files.writeString(project.resolve("src/main/resources/value.txt"), "changed-resource");
                        return new BuildService().build(project, config, project.resolve("cache"));
                    }).get(20, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                }
                Files.writeString(project.resolve("release"), "continue");
                assertTrue(process.waitFor(15, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                assertEquals("original:original-resource", output.strip());
                return new JavaRunner.ProcessResult(process.exitValue(), output);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        });

        run(mode, config, runner);

        assertSnapshotsRemoved();
        assertTrue(Files.isRegularFile(project.resolve("target/classes/example/Late.class")));
        assertEquals("changed-resource", Files.readString(project.resolve("target/classes/value.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"classes", "thin", "uber"})
    void cleanBeforeProcessLaunchCannotRemoveCapturedInputs(String mode) throws Exception {
        prepare();
        Files.writeString(project.resolve("release"), "continue");
        ProjectConfig config = config(mode);
        JavaRunner runner = new JavaRunner(java.io.File.pathSeparator, (command, outputConsumer) -> {
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> new CleanService().clean(project, config)).get(10, TimeUnit.SECONDS);
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                try {
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertEquals(0, process.exitValue(), output);
                    assertEquals("original:original-resource", output.strip());
                    return new JavaRunner.ProcessResult(process.exitValue(), output);
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
            } catch (Exception exception) {
                throw new AssertionError(exception);
            } finally {
                executor.shutdownNow();
            }
        });

        run(mode, config, runner);

        assertFalse(Files.exists(project.resolve("target")));
        assertSnapshotsRemoved();
    }

    @Test
    void failedApplicationRemovesItsSnapshotAndLeavesBuiltOutputs() throws Exception {
        prepare();
        JavaRunner runner = new JavaRunner(java.io.File.pathSeparator,
                (command, outputConsumer) -> new JavaRunner.ProcessResult(1, "application failed"));

        assertThrows(JavaRunException.class, () -> run("classes", config("classes"), runner));

        assertSnapshotsRemoved();
        assertTrue(Files.isRegularFile(project.resolve("target/classes/example/Main.class")));
    }

    private void run(String mode, ProjectConfig config, JavaRunner runner) {
        JdkDetector jdk = new JdkDetector();
        if (mode.equals("classes")) {
            new RunService(new BuildService(jdk), jdk, runner, FrameworkRunAugmenter.none())
                    .run(project, config, project.resolve("cache"), List.of(project.toString()));
        } else {
            new RunPackageService(new PackageService(), new BuildService(jdk), new ClasspathBuilder(), jdk, runner)
                    .runPackage(project, config, project.resolve("cache"), List.of(project.toString()));
        }
    }

    private void prepare() throws Exception {
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n");
        Path sources = project.resolve("src/main/java/example");
        Files.createDirectories(sources);
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/value.txt"), "original-resource");
        Files.writeString(sources.resolve("Main.java"), """
                package example;
                import java.nio.file.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        Path gate = Path.of(args[0]);
                        try (var watcher = FileSystems.getDefault().newWatchService()) {
                            gate.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
                            Files.writeString(gate.resolve("ready"), "ready");
                            while (!Files.exists(gate.resolve("release"))) {
                                var key = watcher.take();
                                key.pollEvents();
                                if (!key.reset()) throw new IllegalStateException("gate directory removed");
                            }
                        }
                        System.out.println(Class.forName("example.Late").getMethod("read").invoke(null));
                    }
                }
                """);
        Files.writeString(sources.resolve("Late.java"), lateSource("original"));
    }

    private static String lateSource(String value) {
        return """
                package example;
                public class Late {
                    public static String read() throws Exception {
                        try (var input = Late.class.getResourceAsStream("/value.txt")) {
                            return "%s:" + new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                """.formatted(value);
    }

    private static ProjectConfig config(String mode) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("snapshot", "1.0.0", "example", "" + Runtime.version().feature(),
                                Optional.of("example.Main")),
                        ProjectConfig.defaultRepositories(), Map.of(), Map.of(), BuildSettings.defaults())
                .withPackageSettings(new PackageSettings(mode.equals("uber") ? PackageMode.UBER : PackageMode.THIN));
    }

    private void assertSnapshotsRemoved() throws Exception {
        try (var snapshots = Files.list(project.resolve(".zolt/run"))) {
            assertEquals(0, snapshots.count());
        }
    }
}
