package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.framework.FrameworkRunResult;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RunServiceTest extends RunServiceTestSupport {
    @Test
    void quarkusRunUsesAugmentedRunnerJarWithoutProjectMainClass() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        ProjectConfig config = config(true, Optional.empty());
        Path runnerJar = projectDir.resolve("target/quarkus-app/quarkus-run.jar");
        FrameworkRunResult runResult = new FrameworkRunResult(runnerJar, "Quarkus runner " + runnerJar);
        List<String> command = new ArrayList<>();
        RunService service = service(
                frameworkRunAugmenter(true, Optional.of(runResult), projectDir, config, cacheRoot),
                (actualCommand, outputConsumer) -> {
                    command.addAll(actualCommand);
                    outputConsumer.accept("started\n");
                    return new JavaRunner.ProcessResult(0, "started\n");
                });

        RunResult result = service.run(projectDir, config, cacheRoot, List.of("one", "two"), ignored -> {
        });

        assertTrue(command.contains("-jar"));
        assertTrue(command.contains(runnerJar.toString()));
        assertEquals("one", command.get(command.size() - 2));
        assertEquals("two", command.get(command.size() - 1));
        assertTrue(result.javaRunResult().mainClass().startsWith("Quarkus runner "));
        assertEquals("started\n", result.javaRunResult().output());
    }

    @Test
    void normalRunStillUsesConfiguredMainClassClasspath() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(projectDir);
        Path runtimeJar = cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "runtime jar placeholder");
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:runtime-lib"
                version = "1.0.0"
                lane = "runtime"
                resolvedScope = "runtime"

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []
                """);
        ProjectConfig config = config(false, Optional.of("com.example.Main"));
        List<String> command = new ArrayList<>();
        RunService service = service(
                (actualProjectDirectory, actualConfig, actualCacheRoot) -> Optional.empty(),
                (actualCommand, outputConsumer) -> {
                    command.addAll(actualCommand);
                    return new JavaRunner.ProcessResult(0, "hello\n");
                });

        RunResult result = service.run(projectDir, config, cacheRoot, List.of());

        assertTrue(command.contains("-classpath"));
        assertTrue(command.stream().anyMatch(entry -> entry.contains("runtime-lib-1.0.0.jar")));
        assertTrue(command.contains("com.example.Main"));
        assertEquals("com.example.Main", result.javaRunResult().mainClass());
    }
}
