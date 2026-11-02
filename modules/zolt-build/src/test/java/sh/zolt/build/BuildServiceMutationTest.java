package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.clean.CleanService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class BuildServiceMutationTest {
    @TempDir
    private Path project;

    @Test
    void cleanWaitsUntilTheEntireStandaloneBuildCompletes() throws Exception {
        Path source = project.resolve("src/main/java/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; public class Main {}\n");
        ProjectConfig config = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("race", "1.0.0", "example", "" + Runtime.version().feature(),
                        Optional.of("example.Main")),
                ProjectConfig.defaultRepositories(), Map.of(), Map.of(), BuildSettings.defaults());
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BuildService service = new BuildService(version -> {
            building.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Build was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return new JdkDetector().detect(version);
        });
        Classpath empty = new Classpath(List.of());
        ClasspathSet classpaths = new ClasspathSet(empty, empty, empty, empty, empty, empty);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var build = executor.submit(() -> service.build(project, config, classpaths));
            try {
                assertTrue(building.await(10, TimeUnit.SECONDS));
                var clean = executor.submit(() -> new CleanService().clean(project, config));
                assertThrows(TimeoutException.class, () -> clean.get(1, TimeUnit.SECONDS));
                release.countDown();
                assertEquals(1, build.get(20, TimeUnit.SECONDS).sourceCount());
                assertEquals(1, clean.get(10, TimeUnit.SECONDS).deletedCount());
            } finally {
                release.countDown();
            }
        }
    }
}
